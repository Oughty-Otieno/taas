package com.taas.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.taas.ledger.ActionEvent;

/** An Action Ledger entry as the API returns it. {@code outcome} tells a screen how to colour it. */
public record ActionView(UUID id, long seq, String eventType, String outcome, String subjectId, String tokenId,
        String actorId, String idempotencyKey, String ruleVersion, Map<String, String> payload, Instant occurredAt,
        String prevHash, String hash) {

    public static ActionView of(ActionEvent e) {
        return new ActionView(e.id(), e.seq(), e.type().name(), e.type().outcome().name(), e.subjectId(), e.tokenId(),
                e.actorId(), e.idempotencyKey(), e.ruleVersion(), e.payload(), e.occurredAt(), e.prevHash(), e.hash());
    }
}
