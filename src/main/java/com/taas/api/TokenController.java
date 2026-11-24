package com.taas.api;

import java.time.Clock;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taas.common.NotFoundException;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.Registry;
import com.taas.ledger.Registry.TokenDef;
import com.taas.ledger.TokenLedger;
import com.taas.workflow.Commands;
import com.taas.workflow.WorkflowEngine;

/** The token catalogue, with every figure derived from the ledger: nothing here is a stored balance. */
@RestController
@RequestMapping("/v1")
public class TokenController {

    public record Holder(String accountId, long available, long reserved, long total, double sharePercent) {
    }

    /** Values computed from derived units and the token's published prices. */
    public record Value(Long unitPriceKes, Long valuationKes, Long redeemValueKes, Long ownedValueKes,
            Long liabilityKes) {
    }

    public record TokenSummary(TokenDef token, Long capUnits, TokenLedger.Supply supply, Value value,
            String activeRuleSet, Registry.Business issuer, WorkflowEngine.RedemptionRate redemption) {
    }

    public record TokenDetail(TokenDef token, Long capUnits, TokenLedger.Supply supply, Value value,
            String activeRuleSet, Registry.Business issuer, WorkflowEngine.RedemptionRate redemption,
            List<Holder> holders, List<ActionView> recent) {
    }

    private final Registry registry;
    private final TokenLedger tokenLedger;
    private final ActionLedger actions;
    private final WorkflowEngine engine;
    private final Clock clock;

    public TokenController(Registry registry, TokenLedger tokenLedger, ActionLedger actions, WorkflowEngine engine,
            Clock clock) {
        this.registry = registry;
        this.tokenLedger = tokenLedger;
        this.actions = actions;
        this.engine = engine;
        this.clock = clock;
    }

    @GetMapping("/tokens")
    public List<TokenSummary> list() {
        return registry.tokens().stream().map(t -> {
            TokenLedger.Supply s = tokenLedger.supply(t.tokenId(), t.authorisedSupply());
            return new TokenSummary(t, t.capUnits(), s, value(t, s, rate(t)), activeRuleSet(t),
                    registry.issuerOf(t.tokenId()).orElse(null), rate(t));
        }).toList();
    }

    /**
     * One token with its holders and recent activity. A customer sees the token's public figures but only their own
     * holding and the activity that involves their account, never other customers' trades or balances.
     */
    @GetMapping("/tokens/{tokenId}")
    public TokenDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable String tokenId) {
        String bound = Http.boundAccount(jwt);
        TokenDef t = registry.token(tokenId).orElseThrow(() -> new NotFoundException("Token " + tokenId + " not found"));
        TokenLedger.Supply s = tokenLedger.supply(tokenId, t.authorisedSupply());
        long base = Math.max(1, s.issued() - s.redeemed());
        List<TokenLedger.Holding> visible = bound == null ? tokenLedger.holders(tokenId, 20)
                : java.util.stream.Stream.of(tokenLedger.holding(tokenId, bound))
                        .filter(h -> h.available() != 0 || h.reserved() != 0).toList();
        List<Holder> holders = visible.stream()
                .map(h -> new Holder(h.accountId(), h.available(), h.reserved(), h.total(),
                        Math.round(h.total() * 10000.0 / (t.kind() == Registry.TokenKind.ASSET ? t.authorisedSupply()
                                : base)) / 100.0))
                .toList();
        ActionLedger.Filter f = new ActionLedger.Filter(tokenId, null, bound, null, List.of(), null, null, null);
        List<ActionView> recent = actions.list(f, null, 15, true).stream().map(ActionView::of).toList();
        return new TokenDetail(t, t.capUnits(), s, value(t, s, rate(t)), activeRuleSet(t),
                registry.issuerOf(tokenId).orElse(null), rate(t), holders, recent);
    }

    @PostMapping("/tokens")
    public ResponseEntity<Object> define(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody Commands.DefineToken body) {
        return Http.send(engine.defineToken(Http.caller(jwt, key), body));
    }

    /** Mint into the treasury. Admin only (token:issue). */
    @PostMapping("/issuances")
    public ResponseEntity<Object> issue(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody Commands.Issue body) {
        return Http.send(engine.issue(Http.caller(jwt, key), body));
    }

    private String activeRuleSet(TokenDef t) {
        if (t.ruleSetId() == null) {
            return t.policyRuleVersion();
        }
        return registry.activeRuleSet(t.ruleSetId(), clock.instant()).map(v -> v.ruleSetId() + "@" + v.version())
                .orElse(t.ruleSetId() + " (no version in force)");
    }

    private WorkflowEngine.RedemptionRate rate(TokenDef t) {
        return engine.redemptionRate(t, clock.instant()).orElse(null);
    }

    /** Redeem value and liability use the rate in force, which a rule version may set. */
    private static Value value(TokenDef t, TokenLedger.Supply s, WorkflowEngine.RedemptionRate rate) {
        if (t.kind() == Registry.TokenKind.ASSET) {
            Long owned = t.unitPriceKes() == null ? null : t.unitPriceKes() * s.circulating();
            return new Value(t.unitPriceKes(), t.valuationKes(), null, owned, null);
        }
        Long perUnit = rate == null ? null : rate.kesPerUnit();
        Long liability = perUnit == null ? null : perUnit * s.circulating();
        return new Value(null, null, perUnit, null, liability);
    }
}
