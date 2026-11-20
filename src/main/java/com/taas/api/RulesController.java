package com.taas.api;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taas.ledger.Registry;
import com.taas.policy.PolicyDecisionPoint;
import com.taas.common.InvalidRequest;
import com.taas.rules.RuleEngine;
import com.taas.rules.RuleSet;
import com.taas.rules.RuleSimulator;
import com.taas.workflow.Commands;
import com.taas.workflow.WorkflowEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rule sets are versioned documents; publishing one is itself a ledger event. Token policies (the cap, supply,
 * KYC, holds) are listed alongside with where each is enforced, because not every rule belongs to the engine.
 */
@RestController
@RequestMapping("/v1/rules")
public class RulesController {

    public record Version(String version, Instant effectiveFrom, String publishedBy, Instant publishedAt,
            long publishedSeq, String status, JsonNode document) {
    }

    public record RuleSetView(String ruleSetId, String activeVersion, List<Version> versions) {
    }

    public record Policy(String tokenId, String name, String value, String enforcedAt, String mechanism) {
    }

    public record Catalogue(List<RuleSetView> ruleSets, List<Policy> policies, String overlapResolution) {
    }

    public record SimulateRequest(@NotNull JsonNode document, Instant from, Instant to) {
    }

    /** Evaluate one transaction against a draft document, or against the version in force when none is given. */
    public record EvaluateRequest(JsonNode document, String ruleSetId, @NotNull @Valid Commands.Transaction transaction,
            String accountId) {
    }

    public record Evaluation(String ruleSet, Map<String, Long> awarded, List<RuleEngine.Step> trace) {
    }

    private final Registry registry;
    private final WorkflowEngine engine;
    private final RuleSimulator simulator;
    private final PolicyDecisionPoint pdp;
    private final ObjectMapper json;
    private final Clock clock;
    private final RuleEngine rules;

    public RulesController(Registry registry, WorkflowEngine engine, RuleSimulator simulator, PolicyDecisionPoint pdp,
            ObjectMapper json, Clock clock, RuleEngine rules) {
        this.rules = rules;
        this.registry = registry;
        this.engine = engine;
        this.simulator = simulator;
        this.pdp = pdp;
        this.json = json;
        this.clock = clock;
    }

    @GetMapping
    public Catalogue catalogue() {
        Instant now = clock.instant();
        Map<String, List<Registry.RuleSetVersion>> byId = new LinkedHashMap<>();
        registry.ruleSetVersions(null).forEach(v -> byId.computeIfAbsent(v.ruleSetId(), k -> new ArrayList<>()).add(v));
        List<RuleSetView> sets = byId.entrySet().stream().map(en -> {
            Optional<Registry.RuleSetVersion> active = registry.activeRuleSet(en.getKey(), now);
            List<Version> versions = en.getValue().stream().map(v -> new Version(v.version(), v.effectiveFrom(),
                    v.publishedBy(), v.publishedAt(), v.publishedSeq(),
                    active.isPresent() && active.get().version().equals(v.version()) ? "IN_FORCE"
                            : v.effectiveFrom().isAfter(now) ? "SCHEDULED" : "SUPERSEDED",
                    json.readTree(v.document()))).toList();
            return new RuleSetView(en.getKey(), active.map(Registry.RuleSetVersion::version).orElse(null), versions);
        }).toList();

        List<Policy> policies = new ArrayList<>();
        for (Registry.TokenDef t : registry.tokens()) {
            if (t.capUnits() != null) {
                policies.add(new Policy(t.tokenId(), "Ownership cap", (t.maxHolderBps() / 100.0) + "% (" + t.capUnits()
                        + " units)", "Database", "CHECK on the derived holding, under the token row lock"));
            }
            policies.add(new Policy(t.tokenId(), "Supply ceiling", t.authorisedSupply() + " units", "Database",
                    "CHECK on the derived @mint holding"));
            if (t.kind() == Registry.TokenKind.ASSET) {
                policies.add(new Policy(t.tokenId(), "Reservation timeout", t.reservationMinutes() + " minutes",
                        "Workflow timer", "Compensating movement, TOKEN_RESERVATION_EXPIRED"));
            }
            if (t.kycRequired()) {
                policies.add(new Policy(t.tokenId(), "Eligibility", "Verified KYC to hold", "Policy decision point",
                        "Denied before the workflow; re-checked at commit"));
            }
            if (t.inactivityMonths() != null) {
                policies.add(new Policy(t.tokenId(), "Expiry", "Lapse after " + t.inactivityMonths()
                        + " months of inactivity", "Workflow timer", "Durable timer; emits TOKEN_REVOKED"));
            }
        }
        policies.add(new Policy("*", "KYC tiers", "Tier 1: up to KES " + pdp.tier1LimitKes()
                + " per asset purchase; tier 2: no limit", "Policy decision point",
                "Checked before the workflow and again at commit; a frozen account cannot buy, send or receive"));
        policies.add(new Policy("*", "Account binding", "A customer acts only for their own account",
                "Policy decision point", "account_id claim on the access token, checked on every request"));
        policies.add(new Policy("*", "Velocity", pdp.maxTransfersPerHour() + " transfers per account per hour",
                "Policy decision point", "Counter over the Action Ledger; a deny is a ledger event"));
        return new Catalogue(sets, policies, "Rules run in descending priority (default 0), ties in document order. "
                + "Every matching rule adds its award; a match on a rule with stopOnMatch ends evaluation.");
    }

    /** Publish a new version. Writes RULE_VERSION_PUBLISHED; versions are immutable. */
    @PostMapping("/versions")
    public ResponseEntity<Object> publish(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody Commands.PublishRules body) {
        return Http.send(engine.publishRules(Http.caller(jwt, key), body));
    }

    /** What a rule set would award for one transaction. Writes nothing. */
    @PostMapping("/evaluate")
    public Evaluation evaluate(@Valid @RequestBody EvaluateRequest body) {
        RuleSet ruleSet;
        String name;
        if (body.document() != null && !body.document().isNull()) {
            ruleSet = engine.parseRuleSet(body.document());
            name = ruleSet.ruleSetId() + "@" + ruleSet.version() + " (draft)";
        } else {
            String id = body.ruleSetId() == null ? "loyalty.default" : body.ruleSetId();
            Registry.RuleSetVersion v = registry.activeRuleSet(id, clock.instant())
                    .orElseThrow(() -> new InvalidRequest("Rule set " + id + " has no version in force"));
            ruleSet = RuleSet.parse(json.readTree(v.document()), new ArrayList<>());
            name = id + "@" + v.version();
        }
        Commands.Transaction t = body.transaction();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", t.type());
        event.put("amountKes", t.amountKes());
        event.put("merchantId", t.merchantId() == null ? "" : t.merchantId());
        event.put("channel", t.channel() == null ? "" : t.channel());
        event.put("accountId", body.accountId() == null ? "" : body.accountId());
        RuleEngine.Evaluation result = rules.evaluate(ruleSet, event);
        return new Evaluation(name, result.unitsByToken(), result.trace());
    }

    /** Dry-run a rule set over historical events. Writes nothing. */
    @PostMapping("/simulate")
    public RuleSimulator.Result simulate(@Valid @RequestBody SimulateRequest body) {
        RuleSet proposed = engine.parseRuleSet(body.document());
        return simulator.simulate(proposed, body.from(), body.to());
    }
}
