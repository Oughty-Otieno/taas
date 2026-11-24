package com.taas.outbox;

import org.springframework.stereotype.Component;

import com.taas.ledger.ActionEvent;
import com.taas.ledger.EventType;
import com.taas.payment.PaymentGateway;

/**
 * Talks to the payment provider once a transaction has committed: charges the buyer for an order, and pays the
 * holder for a redemption.
 */
@Component
public class PaymentDispatcher implements OutboxSubscriber {

    private final PaymentGateway gateway;

    public PaymentDispatcher(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void onEvent(ActionEvent e) {
        if (e.type() == EventType.TOKEN_REDEEMED) {
            String value = e.opt("valueKes");
            if (value != null && Long.parseLong(value) > 0) {
                gateway.payout(new PaymentGateway.Payout(e.id(), Long.parseLong(value), e.get("accountId"),
                        e.opt("payoutTo")));
            }
            return;
        }
        if (e.type() != EventType.ORDER_PLACED) {
            return;
        }
        gateway.charge(new PaymentGateway.Charge(e.getUuid("orderId"), e.idempotencyKey(), e.getLong("amountKes"),
                e.get("accountId"), e.get("paymentMethod"), e.opt("simulateOutcome")));
    }
}
