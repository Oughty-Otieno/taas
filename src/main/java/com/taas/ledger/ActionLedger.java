package com.taas.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The Action Ledger: an append-only, hash-chained log of every event. It is the source of truth; the
 * database refuses UPDATE, DELETE and TRUNCATE on it (see V1__ledgers.sql).
 */
@Repository
public class ActionLedger {

    public static final String GENESIS_HASH = "0".repeat(64);
    /** Single writer lock: appends are serialised so sequence numbers are gapless and the chain is linear. */
    private static final long WRITE_LOCK_KEY = 0x7AA5L;
    private static final TypeReference<TreeMap<String, String>> PAYLOAD_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<ActionEvent> rowMapper = this::mapRow;

    public ActionLedger(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Takes the ledger write lock until the surrounding transaction ends. */
    public void lockForWrite() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (ResultSetExtractor<Void>) rs -> null, WRITE_LOCK_KEY);
    }

    /** Appends an event. The caller must hold {@link #lockForWrite()} in the same transaction. */
    public ActionEvent append(String txId, EventType type, String tokenCode, SortedMap<String, String> payload,
            String requestHash, Instant now) {
        List<Object[]> last = jdbc.query("SELECT seq, hash FROM action_event ORDER BY seq DESC LIMIT 1",
                (rs, i) -> new Object[] {rs.getLong("seq"), rs.getString("hash")});
        long seq = last.isEmpty() ? 1 : (long) last.get(0)[0] + 1;
        String prevHash = last.isEmpty() ? GENESIS_HASH : (String) last.get(0)[1];
        UUID eventId = UUID.randomUUID();
        // Postgres keeps microseconds; truncate first so the hash recomputes identically after a round trip.
        Instant occurredAt = now.truncatedTo(ChronoUnit.MICROS);
        SortedMap<String, String> fields = new TreeMap<>(payload);
        String hash = ActionEvent.computeHash(seq, eventId, txId, type, tokenCode, fields, requestHash, occurredAt,
                prevHash);

        jdbc.update("""
                INSERT INTO action_event (seq, event_id, tx_id, type, token_code, payload, request_hash,
                                          occurred_at, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
                seq, eventId, txId, type.name(), tokenCode, json.writeValueAsString(fields), requestHash,
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC), prevHash, hash);
        return new ActionEvent(seq, eventId, txId, type, tokenCode, fields, requestHash, occurredAt, prevHash, hash);
    }

    public Optional<ActionEvent> findByTxId(String txId) {
        return jdbc.query("SELECT * FROM action_event WHERE tx_id = ?", rowMapper, txId).stream().findFirst();
    }

    public List<ActionEvent> list(String tokenCode, long afterSeq, int limit) {
        if (tokenCode == null) {
            return jdbc.query("SELECT * FROM action_event WHERE seq > ? ORDER BY seq LIMIT ?", rowMapper,
                    afterSeq, limit);
        }
        return jdbc.query("SELECT * FROM action_event WHERE token_code = ? AND seq > ? ORDER BY seq LIMIT ?",
                rowMapper, tokenCode, afterSeq, limit);
    }

    /** Streams every event in sequence order, without loading the whole history into memory. */
    public void forEachInOrder(Consumer<ActionEvent> consumer) {
        jdbc.query(con -> {
            var statement = con.prepareStatement("SELECT * FROM action_event ORDER BY seq");
            statement.setFetchSize(500);
            return statement;
        }, (RowCallbackHandler) rs -> consumer.accept(mapRow(rs, 0)));
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM action_event", Long.class);
        return count == null ? 0 : count;
    }

    private ActionEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ActionEvent(
                rs.getLong("seq"),
                rs.getObject("event_id", UUID.class),
                rs.getString("tx_id"),
                EventType.valueOf(rs.getString("type")),
                rs.getString("token_code"),
                json.readValue(rs.getString("payload"), PAYLOAD_TYPE),
                rs.getString("request_hash"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("prev_hash"),
                rs.getString("hash"));
    }
}
