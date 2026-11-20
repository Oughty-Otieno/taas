package com.taas.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taas.common.NotFoundException;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.Registry;
import com.taas.ledger.TokenLedger;
import com.taas.workflow.Commands;
import com.taas.workflow.WorkflowEngine;

/** The token operations. Every write takes an Idempotency-Key header. */
@RestController
@RequestMapping("/v1")
public class OperationsController {

    /** An order as a workflow: where it is, how it got there, and what the buyer owns if it completed. */
    public record OrderState(Registry.Order order, Registry.Reservation reservation, String step,
            List<ActionView> timeline, List<TokenLedger.Holding> portfolio) {
    }

    private final WorkflowEngine engine;
    private final Registry registry;
    private final ActionLedger actions;
    private final TokenLedger tokenLedger;

    public OperationsController(WorkflowEngine engine, Registry registry, ActionLedger actions,
            TokenLedger tokenLedger) {
        this.engine = engine;
        this.registry = registry;
        this.actions = actions;
        this.tokenLedger = tokenLedger;
    }

    /** Submit a qualifying event; the engine decides whether tokens are earned. The caller never names an amount. */
    @PostMapping("/awards")
    public ResponseEntity<Object> award(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody Commands.Award body) {
        return Http.send(engine.award(Http.caller(jwt, key), body));
    }

    @PostMapping("/reservations")
    public ResponseEntity<Object> reserve(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody Commands.Reserve body) {
        return Http.send(engine.reserve(Http.caller(jwt, key), body));
    }

    @GetMapping("/reservations")
    public List<Registry.Reservation> reservations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String tokenId,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) Registry.ReservationStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        String bound = Http.boundAccount(jwt);
        if (bound != null) {
            Http.requireOwn(jwt, accountId == null ? bound : accountId);
            accountId = bound;
        }
        return registry.reservations(tokenId, accountId, status, Math.max(1, Math.min(limit, 500)));
    }

    @DeleteMapping("/reservations/{id}")
    public ResponseEntity<Object> release(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @PathVariable UUID id) {
        return Http.send(engine.release(Http.caller(jwt, key), id));
    }

    /** Confirm a reserved bid; the payment is charged after the order commits. */
    @PostMapping("/orders")
    public ResponseEntity<Object> order(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody Commands.PlaceOrder body) {
        return Http.send(engine.placeOrder(Http.caller(jwt, key), body));
    }

    @GetMapping("/orders/{id}")
    public OrderState orderState(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Registry.Order order = registry.order(id).orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
        Http.requireOwn(jwt, order.accountId());
        Registry.Reservation reservation = registry.reservation(order.reservationId()).orElse(null);
        ActionLedger.Filter f = new ActionLedger.Filter(null, null, null, null, List.of(), null, null,
                order.reservationId().toString());
        List<ActionView> timeline = actions.list(f, null, 50, false).stream().map(ActionView::of).toList();
        String step = switch (order.status()) {
            case AWAITING_PAYMENT -> "Charging " + order.paymentMethod() + "; waiting for the provider's callback";
            case PAID -> "Paid; ownership assigned";
            case PAYMENT_FAILED -> "Payment failed; units returned to the treasury";
            case EXPIRED -> "The hold ran out before payment; units returned to the treasury";
            case REFUND_REQUIRED -> "Payment arrived after the hold ended; refund required";
        };
        List<TokenLedger.Holding> portfolio = order.status() == Registry.OrderStatus.PAID
                ? tokenLedger.portfolio(order.accountId()) : List.of();
        return new OrderState(order, reservation, step, timeline, portfolio);
    }

    /** Holder to holder. Subject to KYC, velocity and the ownership cap. */
    @PostMapping("/transfers")
    public ResponseEntity<Object> transfer(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody Commands.Transfer body) {
        return Http.send(engine.transfer(Http.caller(jwt, key), body));
    }

    @PostMapping("/redemptions")
    public ResponseEntity<Object> redeem(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody Commands.Redeem body) {
        return Http.send(engine.redeem(Http.caller(jwt, key), body));
    }
}
