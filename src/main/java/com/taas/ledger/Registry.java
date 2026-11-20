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

/**
 * Token definitions, reservations, orders and rule-set versions: projections of the Action Ledger, rebuilt by
 * replay. Writes come only from {@link Projector}.
 */
@Repository
public class Registry {

    public enum TokenKind { LOYALTY, ASSET }

    public enum ReservationStatus { HELD, PURCHASED, RELEASED, EXPIRED, PAYMENT_FAILED }

    public enum OrderStatus { AWAITING_PAYMENT, PAID, PAYMENT_FAILED, EXPIRED, REFUND_REQUIRED }

    public enum BusinessStatus { PENDING, VERIFIED, SUSPENDED }

    /** A business: the issuer of an asset or the operator of a loyalty programme. Public company data only. */
    public record Business(String businessId, String name, String registrationNo, String sector,
            String payoutAccount, String description, BusinessStatus status, String statusReason,
            String registeredBy, Instant registeredAt) {
    }

    public record TokenDef(String tokenId, String name, TokenKind kind, String assetType, String backing,
            Long valuationKes, Long unitPriceKes, Long redeemValueKes, long authorisedSupply, Integer maxHolderBps,
            int reservationMinutes, boolean kycRequired, Integer inactivityMonths, String ruleSetId,
            String policyVersion, Instant definedAt) {

        /** Most units one holder may own or have reserved, or null when the token has no cap. */
        public Long capUnits() {
            return maxHolderBps == null ? null : authorisedSupply * maxHolderBps / 10000;
        }

        /** The version string recorded on events this token's policies decided. */
        public String policyRuleVersion() {
            return "policy:" + tokenId + "@" + policyVersion;
        }
    }

    public record Reservation(UUID reservationId, String tokenId, String accountId, long units, long amountKes,
            ReservationStatus status, Instant expiresAt) {
    }

    public record Order(UUID orderId, UUID reservationId, String tokenId, String accountId, long units,
            long amountKes, String paymentMethod, OrderStatus status, String pspReference) {
    }

    public record RuleSetVersion(String ruleSetId, String version, Instant effectiveFrom, String document,
            String publishedBy, Instant publishedAt, long publishedSeq) {
    }

    private final JdbcTemplate jdbc;

    public Registry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Tokens ----------------------------------------------------------------------------------

    public Optional<TokenDef> token(String tokenId) {
        return jdbc.query("SELECT * FROM token WHERE token_id = ?", this::mapToken, tokenId).stream().findFirst();
    }

    /** Locks the token row until the transaction ends: every write to one token is serialised here. */
    public Optional<TokenDef> lockToken(String tokenId) {
        return jdbc.query("SELECT * FROM token WHERE token_id = ? FOR UPDATE", this::mapToken, tokenId).stream()
                .findFirst();
    }

    public List<TokenDef> tokens() {
        return jdbc.query("SELECT * FROM token ORDER BY kind DESC, token_id", this::mapToken);
    }

    void insertToken(ActionEvent e) {
        jdbc.update("""
                INSERT INTO token (token_id, name, kind, asset_type, backing, valuation_kes, unit_price_kes,
                                   redeem_value_kes, authorised_supply, max_holder_bps, reservation_minutes,
                                   kyc_required, inactivity_months, rule_set_id, policy_version, defined_seq,
                                   defined_at, issuer_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                e.tokenId(), e.get("name"), e.get("kind"), e.opt("assetType"), e.opt("backing"),
                optLong(e, "valuationKes"), optLong(e, "unitPriceKes"), optLong(e, "redeemValueKes"),
                e.getLong("authorisedSupply"), optInt(e, "maxHolderBps"), Integer.parseInt(e.get("reservationMinutes")),
                Boolean.parseBoolean(e.get("kycRequired")), optInt(e, "inactivityMonths"), e.opt("ruleSetId"),
                e.get("policyVersion"), e.seq(), utc(e.occurredAt()), e.opt("issuerId"));
    }

    /** The business that issued a token, if it has one. */
    public Optional<Business> issuerOf(String tokenId) {
        return jdbc.query("SELECT b.* FROM business b JOIN token t ON t.issuer_id = b.business_id WHERE t.token_id = ?",
                this::mapBusiness, tokenId).stream().findFirst();
    }

    void assignIssuer(ActionEvent e) {
        jdbc.update("UPDATE token SET issuer_id = ? WHERE token_id = ?", e.get("issuerId"), e.tokenId());
    }

    // --- Businesses ------------------------------------------------------------------------------

    public List<Business> businesses() {
        return jdbc.query("SELECT * FROM business ORDER BY name", this::mapBusiness);
    }

    public Optional<Business> business(String businessId) {
        return jdbc.query("SELECT * FROM business WHERE business_id = ?", this::mapBusiness, businessId).stream()
                .findFirst();
    }

    /** Locks the business row until the transaction ends, so decisions on one business are serialised. */
    public Optional<Business> lockBusiness(String businessId) {
        return jdbc.query("SELECT * FROM business WHERE business_id = ? FOR UPDATE", this::mapBusiness, businessId)
                .stream().findFirst();
    }

    public boolean registrationNoTaken(String registrationNo) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM business WHERE lower(registration_no) = lower(?)",
                Integer.class, registrationNo);
        return n != null && n > 0;
    }

    /** Token ids issued by each business. */
    public Map<String, List<String>> tokensByIssuer() {
        Map<String, List<String>> out = new TreeMap<>();
        jdbc.query("SELECT issuer_id, token_id FROM token WHERE issuer_id IS NOT NULL ORDER BY token_id",
                (RowCallbackHandler) rs -> out.computeIfAbsent(rs.getString(1), k -> new java.util.ArrayList<>())
                        .add(rs.getString(2)));
        return out;
    }

    void insertBusiness(ActionEvent e) {
        jdbc.update("""
                INSERT INTO business (business_id, name, registration_no, sector, payout_account, description, status,
                                      registered_by, registered_seq, registered_at, updated_seq)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)""",
                e.subjectId(), e.get("name"), e.get("registrationNo"), e.opt("sector"), e.opt("payoutAccount"),
                e.opt("description"), e.actorId(), e.seq(), utc(e.occurredAt()), e.seq());
    }

    void updateBusinessStatus(ActionEvent e, BusinessStatus status) {
        jdbc.update("UPDATE business SET status = ?, status_reason = ?, updated_seq = ? WHERE business_id = ?",
                status.name(), e.opt("reason"), e.seq(), e.subjectId());
    }

    private Business mapBusiness(ResultSet rs, int i) throws SQLException {
        return new Business(rs.getString("business_id"), rs.getString("name"), rs.getString("registration_no"),
                rs.getString("sector"), rs.getString("payout_account"), rs.getString("description"),
                BusinessStatus.valueOf(rs.getString("status")), rs.getString("status_reason"),
                rs.getString("registered_by"), rs.getObject("registered_at", OffsetDateTime.class).toInstant());
    }

    // --- Reservations ----------------------------------------------------------------------------

    public Optional<Reservation> reservation(UUID id) {
        return jdbc.query("SELECT * FROM reservation WHERE reservation_id = ?", this::mapReservation, id).stream()
                .findFirst();
    }

    public List<Reservation> reservations(String tokenId, String accountId, ReservationStatus status, int limit) {
        return jdbc.query("""
                SELECT * FROM reservation
                WHERE (?::text IS NULL OR token_id = ?) AND (?::text IS NULL OR account_id = ?)
                  AND (?::text IS NULL OR status = ?)
                ORDER BY reserved_seq DESC LIMIT ?""", this::mapReservation, tokenId, tokenId, accountId, accountId,
                status == null ? null : status.name(), status == null ? null : status.name(), limit);
    }

    /** Held reservations whose hold has run out at {@code now}. */
    public List<UUID> overdueReservations(Instant now) {
        return jdbc.queryForList("""
                SELECT reservation_id FROM reservation WHERE status = 'HELD' AND expires_at <= ?
                ORDER BY expires_at""", UUID.class, utc(now));
    }

    void insertReservation(ActionEvent e) {
        jdbc.update("""
                INSERT INTO reservation (reservation_id, token_id, account_id, units, amount_kes, status, expires_at,
                                         reserved_seq)
                VALUES (?, ?, ?, ?, ?, 'HELD', ?, ?)""",
                e.getUuid("reservationId"), e.tokenId(), e.get("accountId"), e.getLong("units"),
                e.getLong("amountKes"), utc(Instant.parse(e.get("expiresAt"))), e.seq());
    }

    void closeReservation(ActionEvent e, ReservationStatus status) {
        jdbc.update("UPDATE reservation SET status = ?, closed_seq = ? WHERE reservation_id = ?",
                status.name(), e.seq(), e.getUuid("reservationId"));
    }

    // --- Orders ----------------------------------------------------------------------------------

    public Optional<Order> order(UUID id) {
        return jdbc.query("SELECT * FROM purchase_order WHERE order_id = ?", this::mapOrder, id).stream().findFirst();
    }

    public Optional<Order> orderForReservation(UUID reservationId) {
        return jdbc.query("SELECT * FROM purchase_order WHERE reservation_id = ?", this::mapOrder, reservationId)
                .stream().findFirst();
    }

    void insertOrder(ActionEvent e) {
        jdbc.update("""
                INSERT INTO purchase_order (order_id, reservation_id, token_id, account_id, units, amount_kes,
                                            payment_method, status, placed_seq, updated_seq)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'AWAITING_PAYMENT', ?, ?)""",
                e.getUuid("orderId"), e.getUuid("reservationId"), e.tokenId(), e.get("accountId"), e.getLong("units"),
                e.getLong("amountKes"), e.get("paymentMethod"), e.seq(), e.seq());
    }

    void updateOrder(ActionEvent e, OrderStatus status) {
        jdbc.update("""
                UPDATE purchase_order SET status = ?, updated_seq = ?, psp_reference = COALESCE(?, psp_reference)
                WHERE order_id = ?""", status.name(), e.seq(), e.opt("pspReference"), e.getUuid("orderId"));
    }

    /** An expired reservation also ends the order waiting on it, if there is one. */
    void expireOrderFor(ActionEvent e) {
        jdbc.update("""
                UPDATE purchase_order SET status = 'EXPIRED', updated_seq = ?
                WHERE reservation_id = ? AND status = 'AWAITING_PAYMENT'""", e.seq(), e.getUuid("reservationId"));
    }

    // --- Rule sets -------------------------------------------------------------------------------

    public List<RuleSetVersion> ruleSetVersions(String ruleSetId) {
        return jdbc.query("""
                SELECT * FROM rule_set_version WHERE (?::text IS NULL OR rule_set_id = ?)
                ORDER BY rule_set_id, effective_from DESC, published_seq DESC""", this::mapRuleSet, ruleSetId,
                ruleSetId);
    }

    public Optional<RuleSetVersion> ruleSetVersion(String ruleSetId, String version) {
        return jdbc.query("SELECT * FROM rule_set_version WHERE rule_set_id = ? AND version = ?", this::mapRuleSet,
                ruleSetId, version).stream().findFirst();
    }

    /** The version in force at {@code at}: the latest effective one, the latest published breaking ties. */
    public Optional<RuleSetVersion> activeRuleSet(String ruleSetId, Instant at) {
        return jdbc.query("""
                SELECT * FROM rule_set_version WHERE rule_set_id = ? AND effective_from <= ?
                ORDER BY effective_from DESC, published_seq DESC LIMIT 1""", this::mapRuleSet, ruleSetId, utc(at))
                .stream().findFirst();
    }

    void insertRuleSet(ActionEvent e) {
        jdbc.update("""
                INSERT INTO rule_set_version (rule_set_id, version, effective_from, document, published_by,
                                              published_at, published_seq)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)""",
                e.subjectId(), e.get("version"), utc(Instant.parse(e.get("effectiveFrom"))), e.get("document"),
                e.actorId(), utc(e.occurredAt()), e.seq());
    }

    // --- Replay support --------------------------------------------------------------------------

    /** Workflow state as comparable strings, to check a replay reproduced it exactly. */
    public Map<String, String> workflowSnapshot() {
        Map<String, String> out = new TreeMap<>();
        jdbc.query("SELECT reservation_id, status FROM reservation",
                (RowCallbackHandler) rs -> out.put("reservation:" + rs.getString(1), rs.getString(2)));
        jdbc.query("SELECT order_id, status FROM purchase_order",
                (RowCallbackHandler) rs -> out.put("order:" + rs.getString(1), rs.getString(2)));
        jdbc.query("SELECT rule_set_id, version FROM rule_set_version",
                (RowCallbackHandler) rs -> out.put("rules:" + rs.getString(1) + "@" + rs.getString(2), "published"));
        jdbc.query("SELECT token_id, policy_version, coalesce(issuer_id, '-') FROM token",
                (RowCallbackHandler) rs -> out.put("token:" + rs.getString(1), rs.getString(2) + " issuer "
                        + rs.getString(3)));
        jdbc.query("SELECT business_id, status FROM business",
                (RowCallbackHandler) rs -> out.put("business:" + rs.getString(1), rs.getString(2)));
        return out;
    }

    // --- Mapping ---------------------------------------------------------------------------------

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Long optLong(ActionEvent e, String field) {
        String v = e.opt(field);
        return v == null ? null : Long.valueOf(v);
    }

    private static Integer optInt(ActionEvent e, String field) {
        String v = e.opt(field);
        return v == null ? null : Integer.valueOf(v);
    }

    private TokenDef mapToken(ResultSet rs, int i) throws SQLException {
        return new TokenDef(rs.getString("token_id"), rs.getString("name"), TokenKind.valueOf(rs.getString("kind")),
                rs.getString("asset_type"), rs.getString("backing"), rs.getObject("valuation_kes", Long.class),
                rs.getObject("unit_price_kes", Long.class), rs.getObject("redeem_value_kes", Long.class),
                rs.getLong("authorised_supply"), rs.getObject("max_holder_bps", Integer.class),
                rs.getInt("reservation_minutes"), rs.getBoolean("kyc_required"),
                rs.getObject("inactivity_months", Integer.class), rs.getString("rule_set_id"),
                rs.getString("policy_version"), rs.getObject("defined_at", OffsetDateTime.class).toInstant());
    }

    private Reservation mapReservation(ResultSet rs, int i) throws SQLException {
        return new Reservation(rs.getObject("reservation_id", UUID.class), rs.getString("token_id"),
                rs.getString("account_id"), rs.getLong("units"), rs.getLong("amount_kes"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private Order mapOrder(ResultSet rs, int i) throws SQLException {
        return new Order(rs.getObject("order_id", UUID.class), rs.getObject("reservation_id", UUID.class),
                rs.getString("token_id"), rs.getString("account_id"), rs.getLong("units"), rs.getLong("amount_kes"),
                rs.getString("payment_method"), OrderStatus.valueOf(rs.getString("status")),
                rs.getString("psp_reference"));
    }

    private RuleSetVersion mapRuleSet(ResultSet rs, int i) throws SQLException {
        return new RuleSetVersion(rs.getString("rule_set_id"), rs.getString("version"),
                rs.getObject("effective_from", OffsetDateTime.class).toInstant(), rs.getString("document"),
                rs.getString("published_by"), rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                rs.getLong("published_seq"));
    }
}
