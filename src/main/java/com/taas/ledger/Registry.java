package com.taas.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Token definitions (with their workflow rules) and reservations: projections of the Action Ledger. */
@Repository
public class Registry {

    public enum TokenKind { LOYALTY, ASSET }

    public enum ReservationStatus { HELD, PURCHASED, RELEASED, EXPIRED }

    public record TokenDef(String code, String name, TokenKind kind, Long kesPerToken, int reservationMinutes,
            Long maxSupply, Instant createdAt) {
    }

    public record Reservation(UUID id, String tokenCode, String seller, String buyer, long amount,
            ReservationStatus status, Instant expiresAt) {
    }

    private final JdbcTemplate jdbc;

    public Registry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TokenDef> token(String code) {
        return jdbc.query("SELECT * FROM token WHERE code = ?", this::mapToken, code).stream().findFirst();
    }

    public List<TokenDef> tokens() {
        return jdbc.query("SELECT * FROM token ORDER BY code", this::mapToken);
    }

    public Optional<Reservation> reservation(UUID id) {
        return jdbc.query("SELECT * FROM reservation WHERE id = ?", this::mapReservation, id).stream().findFirst();
    }

    /** A token's reservations, newest first, optionally only those in one status. */
    public List<Reservation> reservations(String tokenCode, ReservationStatus status) {
        if (status == null) {
            return jdbc.query("SELECT * FROM reservation WHERE token_code = ? ORDER BY reserved_seq DESC LIMIT 200",
                    this::mapReservation, tokenCode);
        }
        return jdbc.query("""
                SELECT * FROM reservation WHERE token_code = ? AND status = ?
                ORDER BY reserved_seq DESC LIMIT 200""", this::mapReservation, tokenCode, status.name());
    }

    /** Held reservations whose hold has run out at {@code now}. */
    public List<UUID> overdueReservations(Instant now) {
        return jdbc.queryForList("SELECT id FROM reservation WHERE status = 'HELD' AND expires_at <= ? ORDER BY id",
                UUID.class, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    /** Tokens an account currently has held for sale in open reservations, by token. */
    public Map<String, Long> reservedBySeller(String seller) {
        Map<String, Long> out = new TreeMap<>();
        jdbc.query("""
                SELECT token_code, SUM(amount) FROM reservation
                WHERE seller = ? AND status = 'HELD' GROUP BY token_code""",
                (RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getLong(2)), seller);
        return out;
    }

    /** Every reservation's state as id → status, used to compare projections before and after replay. */
    public Map<String, String> reservationSnapshot() {
        Map<String, String> out = new TreeMap<>();
        jdbc.query("SELECT id, status FROM reservation",
                (RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getString(2)));
        return out;
    }

    void insertToken(ActionEvent e) {
        jdbc.update("""
                INSERT INTO token (code, name, kind, kes_per_token, reservation_minutes, max_supply,
                                   created_seq, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                e.tokenCode(), e.get("name"), e.get("kind"), optionalLong(e, "kesPerToken"),
                Integer.parseInt(e.get("reservationMinutes")), optionalLong(e, "maxSupply"), e.seq(),
                OffsetDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC));
    }

    void insertReservation(ActionEvent e) {
        jdbc.update("""
                INSERT INTO reservation (id, token_code, seller, buyer, amount, status, expires_at, reserved_seq)
                VALUES (?, ?, ?, ?, ?, 'HELD', ?, ?)""",
                e.getUuid("reservationId"), e.tokenCode(), e.get("seller"), e.get("buyer"), e.getLong("amount"),
                OffsetDateTime.ofInstant(Instant.parse(e.get("expiresAt")), ZoneOffset.UTC), e.seq());
    }

    void closeReservation(ActionEvent e, ReservationStatus status) {
        jdbc.update("UPDATE reservation SET status = ?, closed_seq = ? WHERE id = ?",
                status.name(), e.seq(), e.getUuid("reservationId"));
    }

    void wipe() {
        jdbc.execute("TRUNCATE token, reservation");
    }

    private static Long optionalLong(ActionEvent e, String field) {
        String value = e.payload().get(field);
        return value == null ? null : Long.valueOf(value);
    }

    private TokenDef mapToken(ResultSet rs, int i) throws SQLException {
        return new TokenDef(rs.getString("code"), rs.getString("name"), TokenKind.valueOf(rs.getString("kind")),
                rs.getObject("kes_per_token", Long.class), rs.getInt("reservation_minutes"),
                rs.getObject("max_supply", Long.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private Reservation mapReservation(ResultSet rs, int i) throws SQLException {
        return new Reservation(rs.getObject("id", UUID.class), rs.getString("token_code"), rs.getString("seller"),
                rs.getString("buyer"), rs.getLong("amount"), ReservationStatus.valueOf(rs.getString("status")),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }
}
