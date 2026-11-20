package com.taas.outbox;

import org.springframework.stereotype.Component;

import com.taas.ledger.ActionEvent;
import com.taas.ledger.EventType;
import com.taas.payment.PaymentGateway;

/** Charges the customer once an order has committed, with the order's idempotency key. */
@Component
public class PaymentDispatcher implements OutboxSubscriber {

    private final PaymentGateway gateway;

    public PaymentDispatcher(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void onEvent(ActionEvent e) {
        if (e.type() != EventType.ORDER_PLACED) {
            return;
        }
        gateway.charge(new PaymentGateway.Charge(e.getUuid("orderId"), e.idempotencyKey(), e.getLong("amountKes"),
                e.get("accountId"), e.get("paymentMethod"), e.opt("simulateOutcome")));
    }
}
