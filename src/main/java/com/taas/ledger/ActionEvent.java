package com.taas.ledger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import com.taas.common.Hashes;

/** One immutable entry in the Action Ledger: what happened, who caused it, under which rules, and why. */
public record ActionEvent(
        UUID id,
        long seq,
        EventType type,
        String subjectId,
        String tokenId,
        String actorId,
        String idempotencyKey,
        String ruleVersion,
        SortedMap<String, String> payload,
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

    public String opt(String field) {
        return payload.get(field);
    }

    public long getLong(String field) {
        return Long.parseLong(get(field));
    }

    public UUID getUuid(String field) {
        return UUID.fromString(get(field));
    }

    /** The hash this event should carry, recomputed from its own content and its predecessor's hash. */
    public String expectedHash() {
        return computeHash(id, seq, type, subjectId, tokenId, actorId, idempotencyKey, ruleVersion, payload,
                occurredAt, prevHash);
    }

    /** {@code sha256(prev_hash || canonical(event))}, where the canonical form covers every column. */
    static String computeHash(UUID id, long seq, EventType type, String subjectId, String tokenId, String actorId,
            String idempotencyKey, String ruleVersion, SortedMap<String, String> payload, Instant occurredAt,
            String prevHash) {
        SortedMap<String, String> row = new TreeMap<>();
        row.put("id", id.toString());
        row.put("seq", Long.toString(seq));
        row.put("event_type", type.name());
        row.put("subject_id", subjectId);
        row.put("token_id", tokenId == null ? "" : tokenId);
        row.put("actor_id", actorId);
        row.put("idempotency_key", idempotencyKey);
        row.put("rule_version", ruleVersion);
        row.put("occurred_at", occurredAt.toString());
        row.put("payload", Hashes.canonical(payload));
        return Hashes.hex(Hashes.sha256(Hashes.unhex(prevHash),
                Hashes.canonical(row).getBytes(StandardCharsets.UTF_8)));
    }
}
