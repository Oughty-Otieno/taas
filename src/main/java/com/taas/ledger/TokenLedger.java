package com.taas.ledger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * The Token Ledger: signed movements, and the holdings derived from them. It answers "who owns what right now".
 * Movements are only added by {@link Projector}; holdings are maintained by the database from the movements.
 * Nothing here is read by a write path to decide anything.
 */
@Repository
public class TokenLedger {

    public record Holding(String tokenId, String accountId, long available, long reserved) {
        public long total() {
            return available + reserved;
        }
    }

    public record Supply(long authorised, long issued, long inTreasury, long reserved, long redeemed,
            long circulating, long owners) {
    }

    public record Movement(long id, String tokenId, String accountId, String bucket, long units, UUID actionId,
            long seq, String eventType, String ruleVersion, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public TokenLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(ActionEvent event, MovementRules.Leg leg) {
        jdbc.update("""
                INSERT INTO token_movement (token_id, account_id, bucket, units, action_id, rule_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                event.tokenId(), leg.account(), leg.bucket().name(), leg.units(), event.id(), event.ruleVersion(),
                OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
    }

    public Holding holding(String tokenId, String accountId) {
        return jdbc.query("SELECT * FROM holding WHERE token_id = ? AND account_id = ?", (rs, i) -> new Holding(
                rs.getString("token_id"), rs.getString("account_id"), rs.getLong("available"),
                rs.getLong("reserved")), tokenId, accountId).stream().findFirst()
                .orElse(new Holding(tokenId, accountId, 0, 0));
    }

    /** An account's non-empty holdings across every token. */
    public List<Holding> portfolio(String accountId) {
        return jdbc.query("""
                SELECT * FROM holding WHERE account_id = ? AND (available <> 0 OR reserved <> 0)
                ORDER BY token_id""", (rs, i) -> new Holding(rs.getString("token_id"), rs.getString("account_id"),
                rs.getLong("available"), rs.getLong("reserved")), accountId);
    }

    /** Holders of a token (system accounts excluded), largest first. */
    public List<Holding> holders(String tokenId, int limit) {
        return jdbc.query("""
                SELECT * FROM holding
                WHERE token_id = ? AND left(account_id, 1) <> '@' AND (available <> 0 OR reserved <> 0)
                ORDER BY available + reserved DESC, account_id LIMIT ?""",
                (rs, i) -> new Holding(rs.getString("token_id"), rs.getString("account_id"),
                        rs.getLong("available"), rs.getLong("reserved")), tokenId, limit);
    }

    public Supply supply(String tokenId, long authorised) {
        return jdbc.queryForObject("""
                SELECT
                  COALESCE(-SUM(available) FILTER (WHERE account_id = '@mint'), 0)     AS issued,
                  COALESCE(SUM(available) FILTER (WHERE account_id = '@treasury'), 0) AS treasury,
                  COALESCE(SUM(reserved), 0)                                          AS reserved,
                  COALESCE(SUM(available) FILTER (WHERE account_id = '@burn'), 0)     AS redeemed,
                  COUNT(*) FILTER (WHERE left(account_id, 1) <> '@' AND available + reserved > 0) AS owners
                FROM holding WHERE token_id = ?""", (rs, i) -> {
                    long issued = rs.getLong("issued");
                    long treasury = rs.getLong("treasury");
                    long redeemed = rs.getLong("redeemed");
                    return new Supply(authorised, issued, treasury, rs.getLong("reserved"), redeemed,
                            issued - treasury - redeemed, rs.getLong("owners"));
                }, tokenId);
    }

    /** Raw movements, newest first, filtered by any of token, account and action. */
    public List<Movement> movements(String tokenId, String accountId, UUID actionId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.*, e.seq, e.event_type FROM token_movement m JOIN action_event e ON e.id = m.action_id
                WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (tokenId != null) {
            sql.append(" AND m.token_id = ?");
            args.add(tokenId);
        }
        if (accountId != null) {
            sql.append(" AND m.account_id = ?");
            args.add(accountId);
        }
        if (actionId != null) {
            sql.append(" AND m.action_id = ?");
            args.add(actionId);
        }
        sql.append(" ORDER BY m.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new Movement(rs.getLong("id"), rs.getString("token_id"),
                rs.getString("account_id"), rs.getString("bucket"), rs.getLong("units"),
                rs.getObject("action_id", UUID.class), rs.getLong("seq"), rs.getString("event_type"),
                rs.getString("rule_version"), rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                args.toArray());
    }

    /** Every non-zero holding as "token|account|bucket" → units, from the derived holding table. */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        jdbc.query("SELECT token_id, account_id, available, reserved FROM holding", (RowCallbackHandler) rs -> {
            String key = rs.getString(1) + "|" + rs.getString(2);
            if (rs.getLong(3) != 0) {
                out.put(key + "|AVAILABLE", rs.getLong(3));
            }
            if (rs.getLong(4) != 0) {
                out.put(key + "|RESERVED", rs.getLong(4));
            }
        });
        return out;
    }

    /** The same, summed directly from the movements: the source the holding table must agree with. */
    public Map<String, Long> sumOfMovements() {
        Map<String, Long> out = new TreeMap<>();
        jdbc.query("""
                SELECT token_id, account_id, bucket, SUM(units) FROM token_movement
                GROUP BY token_id, account_id, bucket HAVING SUM(units) <> 0""",
                (RowCallbackHandler) rs -> out.put(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3),
                        rs.getLong(4)));
        return out;
    }

    /** Sequence numbers of actions whose movements do not sum to zero (should always be empty). */
    public List<Long> unbalancedActions() {
        return jdbc.queryForList("""
                SELECT e.seq FROM token_movement m JOIN action_event e ON e.id = m.action_id
                GROUP BY e.seq HAVING SUM(m.units) <> 0 ORDER BY e.seq""", Long.class);
    }

    public record Inactive(String accountId, long available, Instant lastActivity) {
    }

    /** Holders of a token with available units and no movement since {@code before}. */
    public List<Inactive> inactiveHolders(String tokenId, Instant before) {
        return jdbc.query("""
                SELECT h.account_id, h.available, MAX(m.created_at) AS last_activity
                FROM holding h JOIN token_movement m ON m.token_id = h.token_id AND m.account_id = h.account_id
                WHERE h.token_id = ? AND left(h.account_id, 1) <> '@' AND h.available > 0
                GROUP BY h.account_id, h.available
                HAVING MAX(m.created_at) < ?
                ORDER BY h.account_id""", (rs, i) -> new Inactive(rs.getString("account_id"), rs.getLong("available"),
                rs.getObject("last_activity", OffsetDateTime.class).toInstant()), tokenId,
                OffsetDateTime.ofInstant(before, ZoneOffset.UTC));
    }
}
