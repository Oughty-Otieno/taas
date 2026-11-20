package com.taas.business;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.taas.common.InvalidRequest;
import com.taas.common.NotFoundException;
import com.taas.common.Rejection;
import com.taas.ledger.ActionEvent;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.EventType;
import com.taas.ledger.Registry;
import com.taas.ledger.Registry.Business;
import com.taas.ledger.Registry.BusinessStatus;
import com.taas.workflow.CommandRunner;
import com.taas.workflow.CommandRunner.Caller;
import com.taas.workflow.CommandRunner.Command;
import com.taas.workflow.Reply;
import com.taas.workflow.WorkflowEngine;

/**
 * Businesses: the issuers behind tokens. An admin registers a business; a compliance officer, never the same
 * person, verifies it (know-your-business) or suspends it. Only a verified business can have assets listed under
 * it, and a suspended one cannot issue or sell new units. Every step is a ledger event, and the business directory
 * is a projection that replay rebuilds.
 *
 * <pre>
 *   register ─▶ PENDING ─verify─▶ VERIFIED ─suspend─▶ SUSPENDED ─verify─▶ VERIFIED
 * </pre>
 */
@Service
public class BusinessService {

    static final String RULES = "kyb-policy@v1";

    public record Register(
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,47}") String businessId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 40) String registrationNo,
            @Size(max = 60) String sector,
            @Size(max = 60) String payoutAccount,
            @Size(max = 300) String description) {
    }

    public record Decision(@NotBlank @Pattern(regexp = "VERIFY|SUSPEND") String decision,
            @Size(max = 300) String reason) {
    }

    public record AssignIssuer(@NotBlank @Size(max = 64) String issuerId) {
    }

    private final CommandRunner runner;
    private final Registry registry;
    private final ActionLedger actions;
    private final WorkflowEngine engine;

    public BusinessService(CommandRunner runner, Registry registry, ActionLedger actions, WorkflowEngine engine) {
        this.runner = runner;
        this.registry = registry;
        this.actions = actions;
        this.engine = engine;
    }

    public Reply register(Caller caller, Register c) {
        String id = c.businessId() != null ? c.businessId() : slug(c.name());
        Command cmd = new Command("register-business", id, null, c);
        return runner.execute(caller, cmd, null, tx -> {
            actions.lockChain();   // serialises registrations, so two cannot race to one id or number
            if (registry.business(id).isPresent()) {
                throw Rejection.conflict("BUSINESS_EXISTS", "Business " + id + " is already registered");
            }
            if (registry.registrationNoTaken(c.registrationNo().trim())) {
                throw Rejection.conflict("REGISTRATION_EXISTS", "Registration number " + c.registrationNo()
                        + " belongs to a business already registered");
            }
            ActionEvent e = tx.record(EventType.BUSINESS_REGISTERED, id, null, RULES, fields("name", c.name().trim(),
                    "registrationNo", c.registrationNo().trim(), "sector", c.sector(), "payoutAccount",
                    c.payoutAccount(), "description", c.description()));
            return Reply.of(201, body("actionId", e.id(), "seq", e.seq(), "businessId", id, "status",
                    BusinessStatus.PENDING));
        });
    }

    /** Verify (or reinstate) a business, or suspend it. Four eyes: the person who registered it cannot verify it. */
    public Reply decide(Caller caller, String businessId, Decision c) {
        if ("SUSPEND".equals(c.decision()) && (c.reason() == null || c.reason().isBlank())) {
            throw new InvalidRequest("A reason is required to suspend a business");
        }
        Command cmd = new Command("business-decision", businessId, null, Map.of("businessId", businessId,
                "decision", c));
        return runner.execute(caller, cmd, null, tx -> {
            Business b = registry.lockBusiness(businessId)
                    .orElseThrow(() -> new NotFoundException("Business " + businessId + " not found"));
            if ("VERIFY".equals(c.decision())) {
                if (b.status() == BusinessStatus.VERIFIED) {
                    throw Rejection.conflict("ALREADY_VERIFIED", b.name() + " is already verified");
                }
                if (b.status() == BusinessStatus.PENDING && b.registeredBy().equals(caller.clientId())) {
                    throw new Rejection(HttpStatus.FORBIDDEN, "FOUR_EYES", caller.clientId() + " registered "
                            + b.name() + " and so cannot also verify it; another officer must");
                }
                ActionEvent e = tx.record(EventType.BUSINESS_VERIFIED, businessId, null, RULES, fields("name",
                        b.name(), "previousStatus", b.status(), "decidedBy", caller.clientId(), "reason", c.reason()));
                return Reply.of(200, body("actionId", e.id(), "businessId", businessId, "status",
                        BusinessStatus.VERIFIED));
            }
            if (b.status() != BusinessStatus.VERIFIED) {
                throw Rejection.conflict("NOT_VERIFIED", b.name() + " is " + b.status().name().toLowerCase()
                        + "; only a verified business can be suspended");
            }
            ActionEvent e = tx.record(EventType.BUSINESS_SUSPENDED, businessId, null, RULES, fields("name", b.name(),
                    "decidedBy", caller.clientId(), "reason", c.reason()));
            return Reply.of(200, body("actionId", e.id(), "businessId", businessId, "status",
                    BusinessStatus.SUSPENDED));
        });
    }

    /** Names the issuer of a token defined without one (for example, before businesses were recorded). */
    public Reply assignIssuer(Caller caller, String tokenId, AssignIssuer c) {
        Command cmd = new Command("assign-issuer", tokenId, tokenId, c);
        return runner.execute(caller, cmd, null, tx -> {
            Registry.TokenDef token = registry.lockToken(tokenId)
                    .orElseThrow(() -> new NotFoundException("Token " + tokenId + " not found"));
            registry.issuerOf(tokenId).ifPresent(b -> {
                throw Rejection.conflict("ISSUER_ALREADY_SET", tokenId + " is already issued by " + b.name());
            });
            engine.requireVerifiedIssuer(c.issuerId());
            ActionEvent e = tx.record(EventType.TOKEN_ISSUER_ASSIGNED, tokenId, tokenId, token.policyRuleVersion(),
                    fields("issuerId", c.issuerId(), "assignedBy", caller.clientId()));
            return Reply.of(200, body("actionId", e.id(), "tokenId", tokenId, "issuerId", c.issuerId()));
        });
    }

    private static String slug(String name) {
        String s = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 48) {
            s = s.substring(0, 48).replaceAll("-+$", "");
        }
        return s.length() < 2 ? "business" : s;
    }

    private static SortedMap<String, String> fields(Object... pairs) {
        SortedMap<String, String> out = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                out.put((String) pairs[i], pairs[i + 1].toString());
            }
        }
        return out;
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
