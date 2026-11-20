package com.taas.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taas.common.InvalidRequest;
import com.taas.common.NotFoundException;
import com.taas.ledger.Projector;
import com.taas.ledger.Registry;
import com.taas.ledger.TokenLedger;
import com.taas.workflow.CommandRunner.Caller;
import com.taas.workflow.Commands;
import com.taas.workflow.Reply;
import com.taas.workflow.WorkflowEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Demo support, off unless {@code taas.demo-endpoints=true}: a token endpoint standing in for the identity
 * provider, example data, and the 200-parallel-bid test. Everything here goes through the same engine, so the
 * example data and the bid storm are recorded like any other activity.
 */
@RestController
@RequestMapping("/v1/dev")
@ConditionalOnProperty(name = "taas.demo-endpoints", havingValue = "true")
public class DevController {

    public static final Set<String> ALL_SCOPES = Set.of("token:read", "token:issue", "token:award", "token:trade",
            "token:transfer", "token:redeem", "ledger:read", "ledger:admin", "rules:read", "rules:publish",
            "customer:read", "customer:register", "kyc:submit", "kyc:review");

    /** A role someone signs in as. Scopes are what the gateway checks; customers are also bound to one account. */
    public record Persona(String id, String label, String description, List<String> scopes, boolean boundToAccount) {
    }

    public static final List<Persona> PERSONAS = List.of(
            new Persona("demo", "Demo operator", "Every permission, for presentations", ALL_SCOPES.stream().sorted().toList(),
                    false),
            new Persona("admin", "Platform admin", "Defines tokens, issues supply, runs replay and verification",
                    List.of("token:read", "token:issue", "ledger:read", "ledger:admin", "rules:read", "customer:read",
                            "customer:register"), false),
            new Persona("rules-manager", "Rules manager", "Drafts, simulates and publishes rule versions",
                    List.of("token:read", "rules:read", "rules:publish", "ledger:read"), false),
            new Persona("operations", "Operations", "Serves customers: onboarding, awards, trades, transfers",
                    List.of("token:read", "token:award", "token:trade", "token:transfer", "token:redeem", "ledger:read",
                            "rules:read", "customer:read", "customer:register", "kyc:submit"), false),
            new Persona("compliance", "Compliance officer", "Decides referred KYC cases, revokes, erases personal data",
                    List.of("token:read", "ledger:read", "customer:read", "kyc:review"), false),
            new Persona("auditor", "Auditor", "Read-only: ledger, proofs, rules, customers",
                    List.of("token:read", "ledger:read", "rules:read", "customer:read"), false),
            new Persona("customer", "Customer", "A token holder: their own portfolio, purchases and KYC only",
                    List.of("token:read", "token:trade", "token:transfer", "token:redeem", "kyc:submit"), true));

    public record TokenRequest(@Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}") String clientId,
            @Size(max = 20) List<String> scopes, String persona, String accountId) {
    }

    public record StormRequest(String tokenId, @Min(1) @Max(1000) Integer bids, @Min(1) @Max(20) Integer buyers,
            @Min(1) Long units, @Min(10) @Max(3600) Integer holdSeconds) {
    }

    public record BuyerResult(String accountId, int accepted, int rejected, long holding, double sharePercent) {
    }

    public record StormResult(String tokenId, int bids, int buyers, long unitsPerBid, int accepted,
            Map<String, Integer> rejectedByCode, long capUnits, long maxHolderUnits, double maxHolderPercent,
            long treasuryAvailable, long totalReserved, long oversold, boolean capHeld, boolean ledgerConsistent,
            long durationMs, List<BuyerResult> perBuyer) {
    }

    private final JwtIssuer issuer;
    private final WorkflowEngine engine;
    private final Registry registry;
    private final TokenLedger tokenLedger;
    private final Projector projector;
    private final ObjectMapper json;
    private final com.taas.policy.PolicyDecisionPoint pdp;

    public DevController(JwtIssuer issuer, WorkflowEngine engine, Registry registry, TokenLedger tokenLedger,
            Projector projector, ObjectMapper json, com.taas.policy.PolicyDecisionPoint pdp) {
        this.pdp = pdp;
        this.issuer = issuer;
        this.engine = engine;
        this.registry = registry;
        this.tokenLedger = tokenLedger;
        this.projector = projector;
        this.json = json;
    }

    @GetMapping("/personas")
    public List<Persona> personas() {
        return PERSONAS;
    }

    /**
     * Stands in for the identity provider. With {@code persona}, signs in as that role (a customer also names
     * their account); otherwise issues client-credentials style tokens for {@code clientId} with {@code scopes}.
     */
    @PostMapping("/token")
    public Map<String, Object> token(@Valid @RequestBody TokenRequest body) {
        if (body.persona() != null) {
            Persona p = PERSONAS.stream().filter(x -> x.id().equals(body.persona())).findFirst()
                    .orElseThrow(() -> new InvalidRequest("Unknown persona " + body.persona()));
            Map<String, Object> claims = new LinkedHashMap<>();
            String subject = p.id().equals("demo") ? "console" : p.id() + "@taas";
            if (p.boundToAccount()) {
                if (body.accountId() == null || pdp.profile(body.accountId()).isEmpty()) {
                    throw new InvalidRequest("A customer signs in to an existing account: pass accountId");
                }
                claims.put(Http.ACCOUNT_CLAIM, body.accountId());
                subject = "customer:" + body.accountId();
            }
            claims.put("persona", p.id());
            Duration ttl = Duration.ofHours(12);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("access_token", issuer.issue(subject, p.scopes(), ttl, claims));
            out.put("token_type", "Bearer");
            out.put("expires_in", ttl.toSeconds());
            out.put("scope", String.join(" ", p.scopes()));
            out.put("subject", subject);
            out.put("persona", p.id());
            out.put("accountId", body.accountId() != null && p.boundToAccount() ? body.accountId() : null);
            return out;
        }
        String clientId = body.clientId() == null ? "console" : body.clientId();
        List<String> scopes = body.scopes() == null || body.scopes().isEmpty() ? List.copyOf(ALL_SCOPES)
                : body.scopes();
        if (!ALL_SCOPES.containsAll(scopes)) {
            throw new InvalidRequest("Unknown scope; known scopes are " + ALL_SCOPES.stream().sorted().toList());
        }
        Duration ttl = Duration.ofHours(12);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", issuer.issue(clientId, scopes, ttl));
        out.put("token_type", "Bearer");
        out.put("expires_in", ttl.toSeconds());
        out.put("scope", String.join(" ", scopes.stream().sorted().toList()));
        return out;
    }

    /**
     * Example data: a loyalty token and three asset tokens, two rule-set versions, the brief's John and Alice
     * example, a purchase, a failed payment, a reservation left to expire, and two rejections. Fixed keys, so
     * running it again replays rather than duplicates.
     */
    @PostMapping("/seed")
    public List<Map<String, Object>> seed() {
        List<Map<String, Object>> steps = new ArrayList<>();
        step(steps, "Define LOYALTY_MPR", () -> engine.defineToken(c("seed-def-mpr"), new Commands.DefineToken(
                "LOYALTY_MPR", "M-Rewards Points", "LOYALTY", null, "Points earned on settled payments", null, null,
                10L, 100_000_000L, null, null, false, 12, "loyalty.default")));
        step(steps, "Define RWA_APT001", () -> engine.defineToken(c("seed-def-apt"), new Commands.DefineToken(
                "RWA_APT001", "Riverside Apartment 001", "ASSET", "Apartment",
                "3-bedroom apartment, Kilimani, Nairobi; title held by a trustee", 25_000_000L, 25L, null,
                1_000_000L, 20, 15, true, null, null)));
        step(steps, "Define RWA_TWR014", () -> engine.defineToken(c("seed-def-twr"), new Commands.DefineToken(
                "RWA_TWR014", "Telecom Tower 014", "ASSET", "Telecom Tower",
                "Rooftop tower, Nakuru; leased to three operators", 12_000_000L, 1_200L, null, 10_000L, 20, 15, true,
                null, null)));
        step(steps, "Define RWA_FARM07", () -> engine.defineToken(c("seed-def-farm"), new Commands.DefineToken(
                "RWA_FARM07", "Avocado Farm 07", "ASSET", "Agriculture",
                "40-acre avocado farm, Murang'a; export offtake contract", 5_000_000L, 100L, null, 50_000L, 20, 15,
                true, null, null)));
        step(steps, "Publish loyalty.default v6", () -> engine.publishRules(c("seed-rules-v6"),
                new Commands.PublishRules(doc("""
                        {"ruleSetId":"loyalty.default","version":"v6","effectiveFrom":"2026-06-01T00:00:00Z",
                         "description":"One unit per qualifying settled transaction",
                         "rules":[{"id":"tier-base","name":"Base accrual","createdBy":"loyalty-team",
                           "when":"event.type == 'TXN_SETTLED' && event.amountKes >= 1000",
                           "then":{"award":{"tokenId":"LOYALTY_MPR","units":1}},"stopOnMatch":true}]}"""))));
        step(steps, "Publish loyalty.default v7", () -> engine.publishRules(c("seed-rules-v7"),
                new Commands.PublishRules(doc("""
                        {"ruleSetId":"loyalty.default","version":"v7","effectiveFrom":"2026-11-01T00:00:00Z",
                         "description":"Adds a high-spend tier. Overlap is explicit: tier-high runs first and stops.",
                         "rules":[
                          {"id":"tier-base","name":"Base accrual","createdBy":"loyalty-team",
                           "when":"event.type == 'TXN_SETTLED' && event.amountKes >= 1000",
                           "then":{"award":{"tokenId":"LOYALTY_MPR","units":1}},"stopOnMatch":true},
                          {"id":"tier-high","name":"High-spend tier","createdBy":"loyalty-team",
                           "when":"event.type == 'TXN_SETTLED' && event.amountKes >= 50000",
                           "then":{"award":{"tokenId":"LOYALTY_MPR","units":100}},"priority":10,"stopOnMatch":true}]}
                        """))));
        step(steps, "Issue 10,000,000 LOYALTY_MPR", () -> engine.issue(c("seed-iss-mpr"),
                new Commands.Issue("LOYALTY_MPR", 10_000_000, "Programme budget")));
        step(steps, "Issue 1,000,000 RWA_APT001", () -> engine.issue(c("seed-iss-apt"),
                new Commands.Issue("RWA_APT001", 1_000_000, "Tokenisation of title")));
        step(steps, "Issue 10,000 RWA_TWR014", () -> engine.issue(c("seed-iss-twr"),
                new Commands.Issue("RWA_TWR014", 10_000, "Tokenisation of lease")));
        step(steps, "Issue 50,000 RWA_FARM07", () -> engine.issue(c("seed-iss-farm"),
                new Commands.Issue("RWA_FARM07", 50_000, "Tokenisation of farm")));
        step(steps, "John spends KES 60,000 (earns 100 under v7)", () -> engine.award(c("seed-award-john"),
                new Commands.Award("john", null, new Commands.Transaction("TXN_SETTLED", 60_000, "naivas-westlands",
                        "POS", "TXN-1001"))));
        step(steps, "John transfers 20 to Alice", () -> engine.transfer(c("seed-xfer-john-alice"),
                new Commands.Transfer("LOYALTY_MPR", "john", "alice", 20, "Gift")));
        step(steps, "John redeems 10", () -> engine.redeem(c("seed-redeem-john"),
                new Commands.Redeem("LOYALTY_MPR", "john", 10, "java-house", "Coffee")));
        step(steps, "Mary spends KES 1,200 (earns 1)", () -> engine.award(c("seed-award-mary"),
                new Commands.Award("mary", null, new Commands.Transaction("TXN_SETTLED", 1_200, "quickmart-kisumu",
                        "MPESA", "TXN-1002"))));
        step(steps, "Grace spends KES 800 (does not qualify)", () -> engine.award(c("seed-award-grace"),
                new Commands.Award("grace", null, new Commands.Transaction("TXN_SETTLED", 800, "kiosk-thika",
                        "MPESA", "TXN-1003"))));
        step(steps, "John tries to transfer 500 (rejected)", () -> engine.transfer(c("seed-xfer-too-much"),
                new Commands.Transfer("LOYALTY_MPR", "john", "bob", 500, "Too much")));

        String aliceRes = id(step(steps, "Alice reserves 250 RWA_APT001", () -> engine.reserve(c("seed-res-alice"),
                new Commands.Reserve("RWA_APT001", "alice", 250, null))), "reservationId");
        if (aliceRes != null) {
            step(steps, "Alice orders (M-PESA succeeds)", () -> engine.placeOrder(c("seed-order-alice"),
                    new Commands.PlaceOrder(UUID.fromString(aliceRes), "MPESA", "SUCCESS")));
        }
        String bobRes = id(step(steps, "Bob reserves 1,000 RWA_APT001", () -> engine.reserve(c("seed-res-bob"),
                new Commands.Reserve("RWA_APT001", "bob", 1_000, null))), "reservationId");
        if (bobRes != null) {
            step(steps, "Bob orders (payment fails)", () -> engine.placeOrder(c("seed-order-bob"),
                    new Commands.PlaceOrder(UUID.fromString(bobRes), "MPESA", "FAIL")));
        }
        step(steps, "Peter reserves RWA_APT001 (no KYC: denied)", () -> engine.reserve(c("seed-res-peter"),
                new Commands.Reserve("RWA_APT001", "peter", 100, null)));
        step(steps, "Mary reserves 300 RWA_TWR014 and never pays (expires in 60s)", () -> engine.reserve(
                c("seed-res-mary"), new Commands.Reserve("RWA_TWR014", "mary", 300, 60)));
        step(steps, "Grace reserves 2,500 RWA_TWR014 (above the 20% cap: rejected)", () -> engine.reserve(
                c("seed-res-grace"), new Commands.Reserve("RWA_TWR014", "grace", 2_500, null)));
        return steps;
    }

    /**
     * The concurrency test: many parallel bids against one asset. The database holds the ownership cap and the
     * supply; the result is read back from the derived holdings, not from the bid replies.
     */
    @PostMapping("/bid-storm")
    public StormResult bidStorm(@Valid @RequestBody StormRequest body) throws Exception {
        String tokenId = body.tokenId() == null ? "RWA_TWR014" : body.tokenId();
        Registry.TokenDef token = registry.token(tokenId)
                .orElseThrow(() -> new NotFoundException("Token " + tokenId + " not found"));
        int bids = body.bids() == null ? 200 : body.bids();
        int buyers = body.buyers() == null ? 4 : body.buyers();
        long units = body.units() == null ? 100 : body.units();
        Integer hold = body.holdSeconds() == null ? 60 : body.holdSeconds();
        String run = UUID.randomUUID().toString().substring(0, 8);

        List<Callable<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < bids; i++) {
            String buyer = String.format("bidder-%02d", i % buyers + 1);
            Caller caller = new Caller("bid-storm", "storm-" + run + "-" + i);
            tasks.add(() -> engine.reserve(caller, new Commands.Reserve(tokenId, buyer, units, hold)));
        }
        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        Map<String, Integer> rejected = new TreeMap<>();
        Map<String, int[]> perBuyer = new TreeMap<>();
        int accepted = 0;
        try {
            List<Future<Reply>> results = pool.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) {
                Reply r = results.get(i).get();
                int[] counts = perBuyer.computeIfAbsent(String.format("bidder-%02d", i % buyers + 1), k -> new int[2]);
                if (r.status() == 201) {
                    accepted++;
                    counts[0]++;
                } else {
                    JsonNode b = json.valueToTree(r.body());
                    rejected.merge(b.path("code").asString("HTTP_" + r.status()), 1, Integer::sum);
                    counts[1]++;
                }
            }
        } finally {
            pool.shutdown();
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;

        long cap = token.capUnits() == null ? Long.MAX_VALUE : token.capUnits();
        List<BuyerResult> buyerResults = new ArrayList<>();
        long maxHolder = 0;
        for (Map.Entry<String, int[]> e : perBuyer.entrySet()) {
            long holding = tokenLedger.holding(tokenId, e.getKey()).total();
            maxHolder = Math.max(maxHolder, holding);
            buyerResults.add(new BuyerResult(e.getKey(), e.getValue()[0], e.getValue()[1], holding,
                    pct(holding, token.authorisedSupply())));
        }
        TokenLedger.Supply supply = tokenLedger.supply(tokenId, token.authorisedSupply());
        boolean capHeld = tokenLedger.holders(tokenId, 10_000).stream().allMatch(h -> h.total() <= cap);
        long oversold = Math.max(0, -supply.inTreasury());
        return new StormResult(tokenId, bids, buyers, units, accepted, rejected, token.capUnits() == null ? 0 : cap,
                maxHolder, pct(maxHolder, token.authorisedSupply()), supply.inTreasury(), supply.reserved(), oversold,
                capHeld, projector.verify().consistent(), durationMs, buyerResults);
    }

    private static double pct(long part, long whole) {
        return Math.round(part * 10000.0 / whole) / 100.0;
    }

    private Caller c(String key) {
        return new Caller("console-seed", key);
    }

    private JsonNode doc(String text) {
        return json.readTree(text);
    }

    private Reply step(List<Map<String, Object>> steps, String name, Supplier<Reply> action) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("step", name);
        try {
            Reply r = action.get();
            JsonNode body = json.valueToTree(r.body());
            row.put("status", r.status());
            row.put("replayed", r.replayed());
            if (body.has("code")) {
                row.put("code", body.get("code").asString());
            }
            steps.add(row);
            return r;
        } catch (RuntimeException e) {
            row.put("status", 500);
            row.put("error", e.getMessage());
            steps.add(row);
            return null;
        }
    }

    private String id(Reply reply, String field) {
        if (reply == null || reply.status() >= 300) {
            return null;
        }
        JsonNode v = json.valueToTree(reply.body()).get(field);
        return v == null ? null : v.asString();
    }
}
