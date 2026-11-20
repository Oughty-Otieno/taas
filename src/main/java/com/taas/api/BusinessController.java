package com.taas.api;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.taas.business.BusinessService;
import com.taas.common.NotFoundException;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.Registry;
import com.taas.ledger.TokenLedger;

/** Businesses (issuers) and the tokens listed under each. Reads are public to any signed-in role. */
@RestController
public class BusinessController {

    public record Listing(String tokenId, String name, String kind, String assetType, long authorisedSupply,
            long issued, long circulating, Long unitPriceKes) {
    }

    public record BusinessView(Registry.Business business, List<Listing> tokens) {
    }

    public record BusinessDetail(Registry.Business business, List<Listing> tokens, List<ActionView> history) {
    }

    private final Registry registry;
    private final TokenLedger tokenLedger;
    private final ActionLedger actions;
    private final BusinessService service;

    public BusinessController(Registry registry, TokenLedger tokenLedger, ActionLedger actions,
            BusinessService service) {
        this.registry = registry;
        this.tokenLedger = tokenLedger;
        this.actions = actions;
        this.service = service;
    }

    @GetMapping("/v1/businesses")
    public List<BusinessView> list() {
        Map<String, List<String>> byIssuer = registry.tokensByIssuer();
        return registry.businesses().stream()
                .map(b -> new BusinessView(b, listings(byIssuer.getOrDefault(b.businessId(), List.of())))).toList();
    }

    @GetMapping("/v1/businesses/{businessId}")
    public BusinessDetail get(@PathVariable String businessId) {
        Registry.Business b = registry.business(businessId)
                .orElseThrow(() -> new NotFoundException("Business " + businessId + " not found"));
        List<ActionView> history = actions.concerning(businessId, 100).stream().map(ActionView::of).toList();
        return new BusinessDetail(b, listings(registry.tokensByIssuer().getOrDefault(businessId, List.of())), history);
    }

    /** Register a business (admin, business:manage). It starts PENDING until compliance verifies it. */
    @PostMapping("/v1/businesses")
    public ResponseEntity<Object> register(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody BusinessService.Register body) {
        return Http.send(service.register(Http.caller(jwt, key), body));
    }

    /** Verify or suspend (compliance, business:review). The registrar cannot verify their own registration. */
    @PostMapping("/v1/businesses/{businessId}/decision")
    public ResponseEntity<Object> decide(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @PathVariable String businessId,
            @Valid @RequestBody BusinessService.Decision body) {
        return Http.send(service.decide(Http.caller(jwt, key), businessId, body));
    }

    /** Name the issuer of a token that has none (business:manage). */
    @PostMapping("/v1/tokens/{tokenId}/issuer")
    public ResponseEntity<Object> assignIssuer(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @PathVariable String tokenId,
            @Valid @RequestBody BusinessService.AssignIssuer body) {
        return Http.send(service.assignIssuer(Http.caller(jwt, key), tokenId, body));
    }

    private List<Listing> listings(List<String> tokenIds) {
        return tokenIds.stream().map(id -> registry.token(id).map(t -> {
            TokenLedger.Supply s = tokenLedger.supply(id, t.authorisedSupply());
            return new Listing(id, t.name(), t.kind().name(), t.assetType(), t.authorisedSupply(), s.issued(),
                    s.circulating(), t.unitPriceKes());
        }).orElse(null)).filter(java.util.Objects::nonNull).toList();
    }
}
