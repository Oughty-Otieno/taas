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
            String activeRuleSet, Registry.Business issuer) {
    }

    public record TokenDetail(TokenDef token, Long capUnits, TokenLedger.Supply supply, Value value,
            String activeRuleSet, Registry.Business issuer, List<Holder> holders, List<ActionView> recent) {
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
            return new TokenSummary(t, t.capUnits(), s, value(t, s), activeRuleSet(t),
                    registry.issuerOf(t.tokenId()).orElse(null));
        }).toList();
    }

    @GetMapping("/tokens/{tokenId}")
    public TokenDetail get(@PathVariable String tokenId) {
        TokenDef t = registry.token(tokenId).orElseThrow(() -> new NotFoundException("Token " + tokenId + " not found"));
        TokenLedger.Supply s = tokenLedger.supply(tokenId, t.authorisedSupply());
        long base = Math.max(1, s.issued() - s.redeemed());
        List<Holder> holders = tokenLedger.holders(tokenId, 20).stream()
                .map(h -> new Holder(h.accountId(), h.available(), h.reserved(), h.total(),
                        Math.round(h.total() * 10000.0 / (t.kind() == Registry.TokenKind.ASSET ? t.authorisedSupply()
                                : base)) / 100.0))
                .toList();
        ActionLedger.Filter f = new ActionLedger.Filter(tokenId, null, null, null, List.of(), null, null, null);
        List<ActionView> recent = actions.list(f, null, 15, true).stream().map(ActionView::of).toList();
        return new TokenDetail(t, t.capUnits(), s, value(t, s), activeRuleSet(t),
                registry.issuerOf(tokenId).orElse(null), holders, recent);
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

    private static Value value(TokenDef t, TokenLedger.Supply s) {
        if (t.kind() == Registry.TokenKind.ASSET) {
            Long owned = t.unitPriceKes() == null ? null : t.unitPriceKes() * s.circulating();
            return new Value(t.unitPriceKes(), t.valuationKes(), null, owned, null);
        }
        Long liability = t.redeemValueKes() == null ? null : t.redeemValueKes() * s.circulating();
        return new Value(null, null, t.redeemValueKes(), null, liability);
    }
}
