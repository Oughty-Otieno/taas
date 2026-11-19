package com.taas.api;

import java.time.Instant;
import java.util.Map;

import com.taas.ledger.ActionEvent;

/** An Action Ledger entry as returned by the API. */
public record EventView(long seq, String txId, String type, String token, Map<String, String> payload,
        Instant occurredAt, String prevHash, String hash) {

    static EventView of(ActionEvent e) {
        return new EventView(e.seq(), e.txId(), e.type().name(), e.tokenCode(), e.payload(), e.occurredAt(),
                e.prevHash(), e.hash());
    }
}
