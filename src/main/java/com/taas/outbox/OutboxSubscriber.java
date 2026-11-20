package com.taas.outbox;

import com.taas.ledger.ActionEvent;

/**
 * Something downstream of the commit: the payment service, webhooks, the live stream. In production this is a
 * Kafka consumer group; here the outbox poller calls subscribers in order.
 */
public interface OutboxSubscriber {

    void onEvent(ActionEvent event) throws Exception;
}
