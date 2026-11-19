package com.taas.ledger;

import java.time.Instant;
import java.util.SortedMap;
import java.util.UUID;

/** One immutable entry in the Action Ledger: what happened, to which token, and why. */
public record ActionEvent(
        long seq,
        UUID eventId,
        String txId,
        EventType type,
        String tokenCode,
        SortedMap<String, String> payload,
        String requestHash,
        Instant occurredAt,
        String prevHash,
        String hash) {

    public String get(String field) {
        String value = payload.get(field);
        if (value == null) {
            throw new IllegalStateException("Event " + seq + " (" + type + ") has no '" + field + "'");
        }
        return value;
    }

    public long getLong(String field) {
        return Long.parseLong(get(field));
    }

    public UUID getUuid(String field) {
        return UUID.fromString(get(field));
    }

    /** The hash this event should carry, recomputed from its own content and its predecessor's hash. */
    public String expectedHash() {
        return computeHash(seq, eventId, txId, type, tokenCode, payload, requestHash, occurredAt, prevHash);
    }

    static String computeHash(long seq, UUID eventId, String txId, EventType type, String tokenCode,
            SortedMap<String, String> payload, String requestHash, Instant occurredAt, String prevHash) {
        return Hashes.sha256(String.join("\n",
                Long.toString(seq), eventId.toString(), txId, type.name(), tokenCode, occurredAt.toString(),
                Hashes.canonical(payload), requestHash, prevHash));
    }
}
