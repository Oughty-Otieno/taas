package com.taas.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

import com.taas.common.Hashes;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The Action Ledger: an append-only, hash-chained log of every event. The database refuses UPDATE, DELETE and
 * TRUNCATE on it. Appends are serialised by the chain lock so sequence numbers are gapless and the chain is
 * linear.
 */
@Repository
public class ActionLedger {

    public static final String GENESIS_HASH = "0".repeat(64);
    private static final long CHAIN_LOCK_KEY = 0x7AA5L;
    private static final TypeReference<TreeMap<String, String>> PAYLOAD_TYPE = new TypeReference<>() { };

    /** What a workflow step decides to record; the ledger adds identity, sequence, time and hashes. */
    public record NewEvent(EventType type, String subjectId, String tokenId, String ruleVersion,
            SortedMap<String, String> payload) {
    }

    public record Filter(String tokenId, String actorId, String account, String assetType, List<String> types,
            Instant from, Instant to, String related) {

        public static Filter none() {
            return new Filter(null, null, null, null, List.of(), null, null, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<ActionEvent> rowMapper = this::mapRow;

    public ActionLedger(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Takes the chain lock until the surrounding transaction ends. */
    public void lockChain() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (ResultSetExtractor<Void>) rs -> null, CHAIN_LOCK_KEY);
    }

    /** Appends an event, taking the chain lock first (it is re-entrant within a transaction). */
    public ActionEvent append(NewEvent e, String actorId, String idempotencyKey, Instant now) {
        lockChain();
        List<Object[]> last = jdbc.query("SELECT seq, hash FROM action_event ORDER BY seq DESC LIMIT 1",
                (rs, i) -> new Object[] {rs.getLong("seq"), Hashes.hex(rs.getBytes("hash"))});
        long seq = last.isEmpty() ? 1 : (long) last.get(0)[0] + 1;
        String prevHash = last.isEmpty() ? GENESIS_HASH : (String) last.get(0)[1];
        UUID id = UUID.randomUUID();
        // Postgres keeps microseconds; truncate first so the hash recomputes identically after a round trip.
        Instant occurredAt = now.truncatedTo(ChronoUnit.MICROS);
        SortedMap<String, String> payload = new TreeMap<>(e.payload());
        String hash = ActionEvent.computeHash(id, seq, e.type(), e.subjectId(), e.tokenId(), actorId,
                idempotencyKey, e.ruleVersion(), payload, occurredAt, prevHash);

        jdbc.update("""
                INSERT INTO action_event (id, seq, event_type, subject_id, token_id, actor_id, idempotency_key,
                                          rule_version, payload, prev_hash, hash, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
                id, seq, e.type().name(), e.subjectId(), e.tokenId(), actorId, idempotencyKey, e.ruleVersion(),
                json.writeValueAsString(payload), Hashes.unhex(prevHash), Hashes.unhex(hash),
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        jdbc.update("INSERT INTO outbox (action_id, event_type, created_at) VALUES (?, ?, ?)",
                id, e.type().name(), OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        return new ActionEvent(id, seq, e.type(), e.subjectId(), e.tokenId(), actorId, idempotencyKey,
                e.ruleVersion(), payload, occurredAt, prevHash, hash);
    }

    public Optional<ActionEvent> find(UUID id) {
        return jdbc.query("SELECT * FROM action_event WHERE id = ?", rowMapper, id).stream().findFirst();
    }

    /** The latest payout event (sent or failed) for a redemption, if the provider has answered. */
    public Optional<ActionEvent> payoutFor(UUID redemptionId) {
        return jdbc.query("""
                SELECT * FROM action_event WHERE event_type IN ('PAYOUT_SENT', 'PAYOUT_FAILED')
                  AND payload->>'redemptionId' = ? ORDER BY seq DESC LIMIT 1""", rowMapper, redemptionId.toString())
                .stream().findFirst();
    }

    public Optional<ActionEvent> findBySeq(long seq) {
        return jdbc.query("SELECT * FROM action_event WHERE seq = ?", rowMapper, seq).stream().findFirst();
    }

    /**
     * A page of events matching the filter. Newest first pages backwards from {@code cursor} (exclusive);
     * oldest first pages forwards from it.
     */
    public List<ActionEvent> list(Filter f, Long cursor, int limit, boolean newestFirst) {
        StringBuilder sql = new StringBuilder("SELECT e.* FROM action_event e WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (cursor != null) {
            sql.append(newestFirst ? " AND e.seq < ?" : " AND e.seq > ?");
            args.add(cursor);
        }
        if (f.tokenId() != null) {
            sql.append(" AND e.token_id = ?");
            args.add(f.tokenId());
        }
        if (f.actorId() != null) {
            sql.append(" AND e.actor_id = ?");
            args.add(f.actorId());
        }
        if (f.account() != null) {
            sql.append(" AND (e.payload->>'accountId' = ? OR e.payload->>'fromAccountId' = ?"
                    + " OR e.payload->>'toAccountId' = ?)");
            args.add(f.account());
            args.add(f.account());
            args.add(f.account());
        }
        if (f.assetType() != null) {
            sql.append(" AND e.token_id IN (SELECT token_id FROM token WHERE kind = ? OR asset_type = ?)");
            args.add(f.assetType());
            args.add(f.assetType());
        }
        if (f.types() != null && !f.types().isEmpty()) {
            sql.append(" AND e.event_type IN (").append("?,".repeat(f.types().size() - 1)).append("?)");
            args.addAll(f.types());
        }
        if (f.from() != null) {
            sql.append(" AND e.occurred_at >= ?");
            args.add(OffsetDateTime.ofInstant(f.from(), ZoneOffset.UTC));
        }
        if (f.to() != null) {
            sql.append(" AND e.occurred_at < ?");
            args.add(OffsetDateTime.ofInstant(f.to(), ZoneOffset.UTC));
        }
        if (f.related() != null) {
            sql.append(" AND (e.payload->>'reservationId' = ? OR e.payload->>'orderId' = ?)");
            args.add(f.related());
            args.add(f.related());
        }
        sql.append(" ORDER BY e.seq ").append(newestFirst ? "DESC" : "ASC").append(" LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), rowMapper, args.toArray());
    }

    /**
     * Events about one subject (a business, a staff user), newest first. For a business this includes the tokens
     * listed under it, which name it as their issuer.
     */
    public List<ActionEvent> concerning(String subjectId, int limit) {
        return jdbc.query("""
                SELECT * FROM action_event
                WHERE subject_id = ?
                   OR (event_type IN ('TOKEN_DEFINED', 'TOKEN_ISSUER_ASSIGNED') AND payload->>'issuerId' = ?)
                ORDER BY seq DESC LIMIT ?""", rowMapper, subjectId, subjectId, limit);
    }

    /** Streams every event in sequence order without loading the whole history into memory. */
    public void forEachInOrder(Consumer<ActionEvent> consumer) {
        jdbc.query(con -> {
            var statement = con.prepareStatement("SELECT * FROM action_event ORDER BY seq");
            statement.setFetchSize(500);
            return statement;
        }, (RowCallbackHandler) rs -> consumer.accept(mapRow(rs, 0)));
    }

    /** Events from {@code fromSeq} to the head, in order, at most {@code limit}. */
    public List<ActionEvent> fromSeq(long fromSeq, int limit) {
        return jdbc.query("SELECT * FROM action_event WHERE seq >= ? ORDER BY seq LIMIT ?", rowMapper, fromSeq,
                limit);
    }

    public Optional<ActionEvent> head() {
        return jdbc.query("SELECT * FROM action_event ORDER BY seq DESC LIMIT 1", rowMapper).stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM action_event", Long.class);
        return count == null ? 0 : count;
    }

    /** Transfers out of an account recorded since {@code since}: the velocity policy's counter. */
    public long transfersFrom(String account, Instant since) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM action_event
                WHERE event_type = 'TOKEN_TRANSFERRED' AND payload->>'fromAccountId' = ? AND occurred_at >= ?""",
                Long.class, account, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        return count == null ? 0 : count;
    }

    private ActionEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ActionEvent(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getString("subject_id"),
                rs.getString("token_id"),
                rs.getString("actor_id"),
                rs.getString("idempotency_key"),
                rs.getString("rule_version"),
                json.readValue(rs.getString("payload"), PAYLOAD_TYPE),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                Hashes.hex(rs.getBytes("prev_hash")),
                Hashes.hex(rs.getBytes("hash")));
    }
}
