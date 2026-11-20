package com.taas.workflow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** "Same key, same reply": every answered request, keyed by (client, Idempotency-Key). */
@Repository
public class IdempotencyStore {

    public record Stored(String operation, String requestHash, int status, JsonNode response) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public IdempotencyStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Serialises requests carrying the same key until the transaction ends. */
    void lock(String clientId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(1, hashtext(?))", (ResultSetExtractor<Void>) rs -> null,
                clientId + "\n" + key);
    }

    public Optional<Stored> find(String clientId, String key) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE client_id = ? AND idem_key = ?",
                (rs, i) -> new Stored(rs.getString("operation"), rs.getString("request_hash"),
                        rs.getInt("status_code"), json.readTree(rs.getString("response"))), clientId, key)
                .stream().findFirst();
    }

    void save(String clientId, String key, String operation, String requestHash, int status, Object body,
            Instant now) {
        jdbc.update("""
                INSERT INTO idempotency_record (client_id, idem_key, operation, request_hash, status_code, response,
                                                created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)""", clientId, key, operation, requestHash, status,
                json.writeValueAsString(body), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
