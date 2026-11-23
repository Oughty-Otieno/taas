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
import com.taas.payment.FakePsp;
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
            List<ActionView> timeline, List<TokenLedger.Holding> portfolio, boolean paymentPromptOpen) {
    }

    private final WorkflowEngine engine;
    private final Registry registry;
    private final ActionLedger actions;
    private final TokenLedger tokenLedger;
    private final FakePsp psp;

    public OperationsController(FakePsp psp, WorkflowEngine engine, Registry registry, ActionLedger actions,
            TokenLedger tokenLedger) {
        this.psp = psp;
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

    /** A reservation with its order, if one was placed: whether it is waiting for payment and the prompt is open. */
    public record ReservationView(java.util.UUID reservationId, String tokenId, String accountId, long units,
            long amountKes, Registry.ReservationStatus status, java.time.Instant expiresAt, java.util.UUID orderId,
            Registry.OrderStatus orderStatus, boolean paymentPromptOpen) {
    }

    @GetMapping("/reservations")
    public List<ReservationView> reservations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String tokenId,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) Registry.ReservationStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        String bound = Http.boundAccount(jwt);
        if (bound != null) {
            Http.requireOwn(jwt, accountId == null ? bound : accountId);
            accountId = bound;
        }
        return registry.reservations(tokenId, accountId, status, Math.max(1, Math.min(limit, 500))).stream()
                .map(r -> {
                    Registry.Order o = registry.orderForReservation(r.reservationId()).orElse(null);
                    return new ReservationView(r.reservationId(), r.tokenId(), r.accountId(), r.units(), r.amountKes(),
                            r.status(), r.expiresAt(), o == null ? null : o.orderId(), o == null ? null : o.status(),
                            o != null && psp.prompting(o.orderId()));
                }).toList();
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

    public record PromptAnswer(boolean pay) {
    }

    /**
     * Demo only: the buyer answers the M-PESA prompt of an order placed with {@code simulateOutcome: MANUAL}, as
     * they would by entering their PIN or cancelling on the phone. The fake PSP then sends its signed callback.
     */
    @PostMapping("/orders/{id}/payment-prompt")
    public ResponseEntity<Object> answerPrompt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody PromptAnswer body) {
        Registry.Order order = registry.order(id).orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
        Http.requireOwn(jwt, order.accountId());
        if (!psp.answer(id, body.pay())) {
            throw new NotFoundException("No payment prompt is waiting for order " + id);
        }
        return ResponseEntity.accepted().body(java.util.Map.of("orderId", id, "answer", body.pay() ? "PAY" : "CANCEL",
                "next", "The PSP sends its signed callback; poll GET /v1/orders/" + id));
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
            case AWAITING_PAYMENT -> psp.prompting(id)
                    ? "The " + order.paymentMethod() + " prompt is on the buyer's phone, waiting for them to pay"
                    : "Charging " + order.paymentMethod() + "; waiting for the provider's callback";
            case PAID -> "Paid; ownership assigned";
            case PAYMENT_FAILED -> "Payment failed; units returned to the treasury";
            case EXPIRED -> "The hold ran out before payment; units returned to the treasury";
            case REFUND_REQUIRED -> "Payment arrived after the hold ended; refund required";
        };
        List<TokenLedger.Holding> portfolio = order.status() == Registry.OrderStatus.PAID
                ? tokenLedger.portfolio(order.accountId()) : List.of();
        return new OrderState(order, reservation, step, timeline, portfolio, psp.prompting(id));
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
