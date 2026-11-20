package com.taas.customer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.taas.common.InvalidRequest;
import com.taas.common.NotFoundException;
import com.taas.common.Rejection;
import com.taas.ledger.ActionEvent;
import com.taas.ledger.EventType;
import com.taas.policy.PolicyDecisionPoint;
import com.taas.workflow.CommandRunner;
import com.taas.workflow.CommandRunner.Caller;
import com.taas.workflow.CommandRunner.Command;
import com.taas.workflow.Reply;

/**
 * Onboarding and KYC. Every change is a ledger event (with no token movements) written in the same transaction
 * as the directory update, so "why was this customer allowed to buy?" has an answer with a name and a time.
 *
 * <pre>
 *   register ─▶ NONE ─submit─▶ PENDING ─provider─▶ VERIFIED (tier 1 or 2)
 *                                  │         └──▶ REJECTED
 *                                  └─ referred ─▶ compliance officer decides
 *   VERIFIED ─revoke─▶ FROZEN (keeps its units; cannot buy, send or receive) ─submit─▶ re-verification
 * </pre>
 *
 * The KYC status says what a customer may do; an open case is tracked separately, so a tier-1 customer asking
 * for tier 2 keeps buying under tier 1 while the case runs.
 */
@Service
public class CustomerService {

    static final String RULES = "kyc-policy@v1";

    private final CommandRunner runner;
    private final CustomerDirectory directory;
    private final PolicyDecisionPoint pdp;
    private final Clock clock;

    public CustomerService(CommandRunner runner, CustomerDirectory directory, PolicyDecisionPoint pdp, Clock clock) {
        this.runner = runner;
        this.directory = directory;
        this.pdp = pdp;
        this.clock = clock;
    }

    public Reply register(Caller caller, CustomerCommands.Register c) {
        String accountId = c.accountId() != null ? c.accountId() : generateId(c.fullName());
        Command cmd = new Command("register-customer", accountId, null, c);
        return runner.execute(caller, cmd, null, tx -> {
            if (c.dateOfBirth() != null
                    && Period.between(c.dateOfBirth(), LocalDate.ofInstant(tx.now(), ZoneOffset.UTC)).getYears() < 18) {
                throw Rejection.rule("UNDERAGE", "Customers must be 18 or older");
            }
            if (directory.customer(accountId).isPresent()) {
                throw Rejection.conflict("ACCOUNT_EXISTS", "Account " + accountId + " already exists");
            }
            CustomerDirectory.Pii pii = new CustomerDirectory.Pii(c.fullName().trim(), c.phone(), c.nationalId(),
                    c.dateOfBirth(), null);
            directory.register(accountId, pii, tx.now());
            ActionEvent e = tx.record(EventType.CUSTOMER_REGISTERED, accountId, null, RULES,
                    fields("accountId", accountId, "piiDigest", pii.digest(), "registeredBy", caller.clientId()));
            return Reply.of(201, body("actionId", e.id(), "seq", e.seq(), "accountId", accountId, "kycStatus", "NONE"));
        });
    }

    public Reply submitKyc(Caller caller, String accountId, CustomerCommands.SubmitKyc c) {
        Command cmd = new Command("submit-kyc", accountId, null, Map.of("accountId", accountId, "tier", c.tier()));
        return runner.execute(caller, cmd, () -> pdp.requireActingFor(caller, accountId), tx -> {
            CustomerDirectory.Customer cu = requireCustomer(accountId);
            if (cu.kycReview() != null) {
                throw Rejection.conflict("KYC_IN_PROGRESS", "Case " + cu.kycCaseId() + " is still open ("
                        + cu.kycReview() + ")");
            }
            if ("VERIFIED".equals(cu.kycStatus()) && cu.kycTier() >= c.tier()) {
                throw Rejection.conflict("ALREADY_VERIFIED", accountId + " is already verified at tier " + cu.kycTier());
            }
            CustomerDirectory.Pii pii = directory.pii(accountId).orElse(null);
            if (pii == null || pii.erasedAt() != null) {
                throw Rejection.rule("NO_PERSONAL_DATA", "There is no personal data on file for " + accountId
                        + " to verify; it was imported or erased");
            }
            UUID caseId = UUID.randomUUID();
            // Eligibility does not change while the case runs: NONE/REJECTED become PENDING, VERIFIED and FROZEN stay.
            String status = "VERIFIED".equals(cu.kycStatus()) || "FROZEN".equals(cu.kycStatus()) ? cu.kycStatus()
                    : "PENDING";
            directory.updateKyc(accountId, status, cu.kycTier(), caseId, c.tier(), "AWAITING_PROVIDER", null, tx.now());
            ActionEvent e = tx.record(EventType.KYC_SUBMITTED, accountId, null, RULES, fields("accountId", accountId,
                    "caseId", caseId, "tierRequested", c.tier(), "piiDigest", pii.digest(),
                    "submittedBy", caller.clientId()));
            return Reply.of(202, body("actionId", e.id(), "seq", e.seq(), "caseId", caseId, "kycStatus", status,
                    "review", "AWAITING_PROVIDER"));
        });
    }

    /** The provider's verdict. Keyed by its event id, so a redelivered callback changes nothing. */
    public Reply providerResult(CustomerCommands.KycCallback c) {
        Caller caller = new Caller("kyc-provider", "kyc:" + c.providerEventId());
        Command cmd = new Command("kyc-callback", c.accountId(), null, c);
        return runner.execute(caller, cmd, null, tx -> {
            CustomerDirectory.Customer cu = requireCustomer(c.accountId());
            if (!c.caseId().equals(cu.kycCaseId()) || !"AWAITING_PROVIDER".equals(cu.kycReview())) {
                throw Rejection.conflict("CASE_NOT_OPEN", "Case " + c.caseId() + " is not awaiting the provider");
            }
            return switch (c.result()) {
                case "VERIFIED" -> verify(tx, cu, c.tier() != null ? c.tier() : cu.kycTierRequested(), "provider",
                        c.reason());
                case "REJECTED" -> reject(tx, cu, "provider", c.reason() == null ? "Rejected by the provider" : c.reason());
                default -> {
                    directory.updateKyc(cu.accountId(), cu.kycStatus(), cu.kycTier(), cu.kycCaseId(),
                            cu.kycTierRequested(), "NEEDS_REVIEW", c.reason(), tx.now());
                    ActionEvent e = tx.record(EventType.KYC_REFERRED, cu.accountId(), null, RULES, fields("accountId",
                            cu.accountId(), "caseId", cu.kycCaseId(), "tierRequested", cu.kycTierRequested(),
                            "reason", c.reason() == null ? "Needs manual review" : c.reason()));
                    yield Reply.of(200, body("actionId", e.id(), "review", "NEEDS_REVIEW"));
                }
            };
        });
    }

    /** A compliance officer decides a referred case, or revokes a verified customer (who is then frozen). */
    public Reply decide(Caller caller, String accountId, CustomerCommands.Decision c) {
        if (!"VERIFY".equals(c.decision()) && (c.reason() == null || c.reason().isBlank())) {
            throw new InvalidRequest("A reason is required to " + c.decision().toLowerCase());
        }
        Command cmd = new Command("kyc-decision", accountId, null, Map.of("accountId", accountId, "decision", c));
        return runner.execute(caller, cmd, null, tx -> {
            CustomerDirectory.Customer cu = requireCustomer(accountId);
            String officer = "officer:" + caller.clientId();
            if ("REVOKE".equals(c.decision())) {
                if (!"VERIFIED".equals(cu.kycStatus())) {
                    throw Rejection.conflict("NOT_VERIFIED", accountId + " is " + cu.kycStatus() + ", not VERIFIED");
                }
                directory.updateKyc(accountId, "FROZEN", cu.kycTier(), null, null, null, c.reason(), tx.now());
                ActionEvent e = tx.record(EventType.KYC_REVOKED, accountId, null, RULES, fields("accountId", accountId,
                        "tier", cu.kycTier(), "decidedBy", officer, "reason", c.reason()));
                return Reply.of(200, body("actionId", e.id(), "kycStatus", "FROZEN"));
            }
            if (cu.kycReview() == null) {
                throw Rejection.conflict("NO_OPEN_CASE", accountId + " has no KYC case to decide");
            }
            return "VERIFY".equals(c.decision())
                    ? verify(tx, cu, c.tier() != null ? c.tier() : cu.kycTierRequested(), officer, c.reason())
                    : reject(tx, cu, officer, c.reason());
        });
    }

    /** Erases the personal data. Holdings and the ledger's digest remain; the customer cannot be re-verified. */
    public Reply erasePii(Caller caller, String accountId) {
        Command cmd = new Command("erase-pii", accountId, null, Map.of("accountId", accountId));
        return runner.execute(caller, cmd, null, tx -> {
            requireCustomer(accountId);
            CustomerDirectory.Pii pii = directory.pii(accountId).orElse(null);
            if (pii == null || pii.erasedAt() != null) {
                throw Rejection.conflict("NOTHING_TO_ERASE", "No personal data is held for " + accountId);
            }
            directory.erase(accountId, tx.now());
            ActionEvent e = tx.record(EventType.PII_ERASED, accountId, null, RULES, fields("accountId", accountId,
                    "piiDigest", pii.digest(), "erasedBy", caller.clientId()));
            return Reply.of(200, body("actionId", e.id(), "accountId", accountId, "piiErased", true));
        });
    }

    private Reply verify(CommandRunner.Tx tx, CustomerDirectory.Customer cu, Integer tier, String by, String reason) {
        int granted = Math.max(cu.kycTier(), tier == null ? 1 : tier);
        directory.updateKyc(cu.accountId(), "VERIFIED", granted, null, null, null, reason, tx.now());
        ActionEvent e = tx.record(EventType.KYC_VERIFIED, cu.accountId(), null, RULES, fields("accountId",
                cu.accountId(), "caseId", cu.kycCaseId(), "tier", granted, "decidedBy", by, "reason", reason));
        return Reply.of(200, body("actionId", e.id(), "kycStatus", "VERIFIED", "kycTier", granted));
    }

    private Reply reject(CommandRunner.Tx tx, CustomerDirectory.Customer cu, String by, String reason) {
        // A failed upgrade leaves an existing verification (or a freeze) in place.
        String status = "VERIFIED".equals(cu.kycStatus()) || "FROZEN".equals(cu.kycStatus()) ? cu.kycStatus()
                : "REJECTED";
        directory.updateKyc(cu.accountId(), status, cu.kycTier(), null, null, null, reason, tx.now());
        ActionEvent e = tx.record(EventType.KYC_REJECTED, cu.accountId(), null, RULES, fields("accountId",
                cu.accountId(), "caseId", cu.kycCaseId(), "tierRequested", cu.kycTierRequested(), "decidedBy", by,
                "reason", reason));
        return Reply.of(200, body("actionId", e.id(), "kycStatus", status, "kycTier", cu.kycTier()));
    }

    private CustomerDirectory.Customer requireCustomer(String accountId) {
        return directory.lock(accountId).orElseThrow(() -> new NotFoundException("Customer " + accountId + " not found"));
    }

    private String generateId(String fullName) {
        String first = fullName == null ? "customer" : fullName.trim().toLowerCase().split("\\s+")[0]
                .replaceAll("[^a-z0-9]", "");
        return (first.isEmpty() ? "customer" : first) + "-" + Long.toString(clock.millis() % 46656, 36);
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
