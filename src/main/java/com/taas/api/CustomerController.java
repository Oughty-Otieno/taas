package com.taas.api;

import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.taas.common.InvalidRequest;
import com.taas.common.NotFoundException;
import com.taas.customer.CustomerCommands;
import com.taas.customer.CustomerDirectory;
import com.taas.customer.CustomerService;
import com.taas.customer.KycSignature;
import com.taas.ledger.ActionLedger;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Customers and KYC. Staff with customer:read see everyone (with personal data masked); a customer sees only
 * themselves. KYC outcomes arrive from the verification provider by signed webhook or from a compliance officer.
 */
@RestController
public class CustomerController {

    private static final List<String> HISTORY_TYPES = List.of("CUSTOMER_REGISTERED", "KYC_SUBMITTED", "KYC_REFERRED",
            "KYC_VERIFIED", "KYC_REJECTED", "KYC_REVOKED", "PII_ERASED");

    /** {@code tier1LimitKes}: the most a tier-1 customer may spend on one purchase. */
    public record CustomerDetail(CustomerDirectory.Customer customer, List<ActionView> history, long tier1LimitKes) {
    }

    private final CustomerService service;
    private final CustomerDirectory directory;
    private final ActionLedger actions;
    private final KycSignature signature;
    private final ObjectMapper json;
    private final Validator validator;
    private final com.taas.policy.PolicyDecisionPoint pdp;

    public CustomerController(CustomerService service, CustomerDirectory directory, ActionLedger actions,
            KycSignature signature, ObjectMapper json, Validator validator, com.taas.policy.PolicyDecisionPoint pdp) {
        this.pdp = pdp;
        this.service = service;
        this.directory = directory;
        this.actions = actions;
        this.signature = signature;
        this.json = json;
        this.validator = validator;
    }

    @GetMapping("/v1/customers")
    public List<CustomerDirectory.Customer> customers(@AuthenticationPrincipal Jwt jwt) {
        String bound = Http.boundAccount(jwt);
        return directory.customers().stream().filter(c -> bound == null || bound.equals(c.accountId())).toList();
    }

    /** A customer with their KYC history, read from the Action Ledger. */
    @GetMapping("/v1/customers/{accountId}")
    public CustomerDetail customer(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountId) {
        Http.requireOwn(jwt, accountId);
        CustomerDirectory.Customer c = directory.customer(accountId)
                .orElseThrow(() -> new NotFoundException("Customer " + accountId + " not found"));
        ActionLedger.Filter f = new ActionLedger.Filter(null, null, accountId, null, HISTORY_TYPES, null, null, null);
        return new CustomerDetail(c, actions.list(f, null, 100, true).stream().map(ActionView::of).toList(),
                pdp.tier1LimitKes());
    }

    /** Onboard a customer (staff with customer:register). */
    @PostMapping("/v1/customers")
    public ResponseEntity<Object> register(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody CustomerCommands.Register body) {
        return Http.send(service.register(Http.caller(jwt, key), body));
    }

    /** Start a KYC case (the customer themselves, or staff on their behalf). */
    @PostMapping("/v1/customers/{accountId}/kyc")
    public ResponseEntity<Object> submitKyc(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @PathVariable String accountId,
            @Valid @RequestBody CustomerCommands.SubmitKyc body) {
        return Http.send(service.submitKyc(Http.caller(jwt, key), accountId, body));
    }

    /** A compliance officer's decision: verify or reject a referred case, or revoke (freeze). */
    @PostMapping("/v1/customers/{accountId}/kyc/decision")
    public ResponseEntity<Object> decide(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @PathVariable String accountId,
            @Valid @RequestBody CustomerCommands.Decision body) {
        return Http.send(service.decide(Http.caller(jwt, key), accountId, body));
    }

    /** Erase personal data (right to erasure). The ledger keeps only the digest it always had. */
    @DeleteMapping("/v1/customers/{accountId}/pii")
    public ResponseEntity<Object> erase(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @PathVariable String accountId) {
        return Http.send(service.erasePii(Http.caller(jwt, key), accountId));
    }

    /** The verification provider's webhook, authenticated by its signature. */
    @PostMapping("/v1/kyc/callback")
    public ResponseEntity<Object> callback(
            @RequestHeader(value = KycSignature.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = KycSignature.SIGNATURE_HEADER, required = false) String sig,
            @RequestBody String body) {
        if (!signature.verify(timestamp, sig, body)) {
            return ResponseEntity.status(401).body(java.util.Map.of("title", "Unauthorized", "status", 401,
                    "detail", "Missing, invalid or stale KYC provider signature"));
        }
        CustomerCommands.KycCallback callback;
        try {
            callback = json.readValue(body, CustomerCommands.KycCallback.class);
        } catch (JacksonException e) {
            throw new InvalidRequest("Callback body is not valid JSON for a KYC callback");
        }
        Set<ConstraintViolation<CustomerCommands.KycCallback>> violations = validator.validate(callback);
        if (!violations.isEmpty()) {
            throw new InvalidRequest(violations.stream().map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted().reduce((a, b) -> a + "; " + b).orElse("Invalid callback"));
        }
        return Http.send(service.providerResult(callback));
    }
}
