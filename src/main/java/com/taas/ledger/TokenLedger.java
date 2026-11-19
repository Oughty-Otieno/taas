package com.taas.ledger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * The Token Ledger: double-entry postings and the balances they add up to. It answers "who owns what
 * right now". Nothing here is edited by hand: rows are only added by {@link Projector} from events.
 */
@Repository
public class TokenLedger {

    public record StatementLine(long seq, String txId, EventType type, Instant occurredAt, String counterparty,
            long amount, long runningBalance) {
    }

    public record Holder(String account, long amount) {
    }

    public record Supply(long issued, long redeemed, long circulating, long inReservation) {
    }

    private final JdbcTemplate jdbc;

    public TokenLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void post(ActionEvent event, PostingRules.Leg leg) {
        jdbc.update("INSERT INTO posting (event_seq, tx_id, token_code, account, amount) VALUES (?, ?, ?, ?, ?)",
                event.seq(), event.txId(), event.tokenCode(), leg.account(), leg.amount());
        // Update first, insert only for a new account. (INSERT ... ON CONFLICT would check the overdraft
        // constraint against the proposed row, so a debit of an existing balance would be refused.)
        int updated = jdbc.update("UPDATE balance SET amount = amount + ? WHERE account = ? AND token_code = ?",
                leg.amount(), leg.account(), event.tokenCode());
        if (updated == 0) {
            jdbc.update("INSERT INTO balance (account, token_code, amount) VALUES (?, ?, ?)",
                    leg.account(), event.tokenCode(), leg.amount());
        }
        // Keep the table to non-zero balances so it compares cleanly with the postings it is derived from.
        jdbc.update("DELETE FROM balance WHERE account = ? AND token_code = ? AND amount = 0",
                leg.account(), event.tokenCode());
    }

    public long balance(String account, String tokenCode) {
        List<Long> amounts = jdbc.queryForList(
                "SELECT amount FROM balance WHERE account = ? AND token_code = ?", Long.class, account, tokenCode);
        return amounts.isEmpty() ? 0 : amounts.get(0);
    }

    /** Non-zero balances of one account, by token. */
    public Map<String, Long> balancesOf(String account) {
        Map<String, Long> balances = new TreeMap<>();
        jdbc.query("SELECT token_code, amount FROM balance WHERE account = ?",
                (RowCallbackHandler) rs -> balances.put(rs.getString(1), rs.getLong(2)), account);
        return balances;
    }

    public List<Holder> holders(String tokenCode) {
        return jdbc.query("""
                SELECT account, amount FROM balance
                WHERE token_code = ? AND account NOT LIKE '@%'
                ORDER BY amount DESC, account""",
                (rs, i) -> new Holder(rs.getString(1), rs.getLong(2)), tokenCode);
    }

    public Supply supply(String tokenCode) {
        long issued = -balance(Accounts.MINT, tokenCode);
        long redeemed = balance(Accounts.BURN, tokenCode);
        Long held = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM balance
                WHERE token_code = ? AND account LIKE '@hold:%'""", Long.class, tokenCode);
        return new Supply(issued, redeemed, issued - redeemed, held == null ? 0 : held);
    }

    /** Bank-statement view: every posting on the account with the balance after it. */
    public List<StatementLine> statement(String account, String tokenCode) {
        return jdbc.query("""
                SELECT p.event_seq, e.tx_id, e.type, e.occurred_at, p.amount,
                       (SELECT q.account FROM posting q WHERE q.event_seq = p.event_seq AND q.id <> p.id
                        ORDER BY q.id LIMIT 1) AS counterparty,
                       SUM(p.amount) OVER (ORDER BY p.event_seq, p.id) AS running
                FROM posting p JOIN action_event e ON e.seq = p.event_seq
                WHERE p.account = ? AND p.token_code = ?
                ORDER BY p.event_seq, p.id""",
                (rs, i) -> new StatementLine(rs.getLong("event_seq"), rs.getString("tx_id"),
                        EventType.valueOf(rs.getString("type")),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("counterparty"), rs.getLong("amount"), rs.getLong("running")),
                account, tokenCode);
    }

    /** Every non-zero balance as "account|token" → amount, from the balance table. */
    public Map<String, Long> snapshot() {
        return collect("SELECT account, token_code, amount FROM balance");
    }

    /** The same, recomputed directly from the postings, to check the balance table against its source. */
    public Map<String, Long> sumOfPostings() {
        return collect("""
                SELECT account, token_code, SUM(amount) FROM posting
                GROUP BY account, token_code HAVING SUM(amount) <> 0""");
    }

    /** Events whose postings do not sum to zero (should always be empty). */
    public List<Long> unbalancedEvents() {
        return jdbc.queryForList(
                "SELECT event_seq FROM posting GROUP BY event_seq HAVING SUM(amount) <> 0 ORDER BY event_seq",
                Long.class);
    }

    void wipe() {
        jdbc.execute("TRUNCATE posting, balance RESTART IDENTITY");
    }

    private Map<String, Long> collect(String sql) {
        Map<String, Long> out = new TreeMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> out.put(rs.getString(1) + "|" + rs.getString(2), rs.getLong(3)));
        return out;
    }
}
