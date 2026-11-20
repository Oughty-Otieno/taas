package com.taas.payment;

import java.util.UUID;

/**
 * The payment service the engine charges. The verdict comes back later through the signed callback
 * ({@code POST /v1/payments/callback}), never as this call's return value.
 */
public interface PaymentGateway {

    record Charge(UUID orderId, String idempotencyKey, long amountKes, String accountId, String method,
            String simulateOutcome) {
    }

    /** Asks the provider to collect. Same idempotency key, same charge: calling twice never charges twice. */
    String charge(Charge charge);
}
