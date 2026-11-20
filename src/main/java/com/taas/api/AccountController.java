package com.taas.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taas.ledger.Registry;
import com.taas.ledger.TokenLedger;
import com.taas.policy.PolicyDecisionPoint;

@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    public record PortfolioLine(String tokenId, String name, String kind, String assetType, long available,
            long reserved, Long valueKes) {
    }

    public record Portfolio(String accountId, String displayName, String kycStatus, List<PortfolioLine> holdings) {
    }

    private final TokenLedger tokenLedger;
    private final Registry registry;
    private final PolicyDecisionPoint pdp;

    public AccountController(TokenLedger tokenLedger, Registry registry, PolicyDecisionPoint pdp) {
        this.tokenLedger = tokenLedger;
        this.registry = registry;
        this.pdp = pdp;
    }

    /** Accounts and their KYC state. A customer sees only their own. */
    @GetMapping
    public List<PolicyDecisionPoint.Profile> accounts(@AuthenticationPrincipal Jwt jwt) {
        String bound = Http.boundAccount(jwt);
        return pdp.profiles().stream().filter(p -> bound == null || bound.equals(p.accountId())).toList();
    }

    /** Holdings across loyalty and asset tokens, read from the derived holdings. */
    @GetMapping("/{accountId}/portfolio")
    public Portfolio portfolio(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountId) {
        Http.requireOwn(jwt, accountId);
        var profile = pdp.profile(accountId);
        List<PortfolioLine> lines = tokenLedger.portfolio(accountId).stream().map(h -> {
            Registry.TokenDef t = registry.token(h.tokenId()).orElseThrow();
            Long perUnit = t.kind() == Registry.TokenKind.ASSET ? t.unitPriceKes() : t.redeemValueKes();
            return new PortfolioLine(t.tokenId(), t.name(), t.kind().name(), t.assetType(), h.available(),
                    h.reserved(), perUnit == null ? null : perUnit * h.available());
        }).toList();
        return new Portfolio(accountId, profile.map(PolicyDecisionPoint.Profile::displayName).orElse(accountId),
                profile.map(PolicyDecisionPoint.Profile::kycStatus).orElse("NONE"), lines);
    }
}
