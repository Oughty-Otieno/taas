package com.taas.customer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.taas.common.Hashes;

/**
 * The customer directory (profile and KYC state) and the personal data kept beside it. Both are operational
 * stores, changed only by {@link CustomerService} in the same transaction as the ledger event that records the
 * change. Personal data never enters the ledger: events carry a digest of it.
 */
@Repository
public class CustomerDirectory {

    /** A customer as staff see them: KYC state and masked contact details, never raw identity numbers. */
    public record Customer(String accountId, String displayName, String kycStatus, int kycTier, UUID kycCaseId,
            Integer kycTierRequested, String kycReview, String kycNote, String phoneMasked, String nationalIdMasked,
            boolean piiErased, Instant registeredAt, Instant updatedAt) {
    }

    record Pii(String fullName, String phone, String nationalId, LocalDate dateOfBirth, Instant erasedAt) {

        String digest() {
            SortedMap<String, String> f = new TreeMap<>();
            f.put("fullName", fullName == null ? "" : fullName);
            f.put("phone", phone == null ? "" : phone);
            f.put("nationalId", nationalId == null ? "" : nationalId);
            f.put("dateOfBirth", dateOfBirth == null ? "" : dateOfBirth.toString());
            return Hashes.sha256Hex(Hashes.canonical(f));
        }
    }

    private static final String SELECT = """
            SELECT p.*, x.phone, x.national_id, x.erased_at
            FROM account_profile p LEFT JOIN customer_pii x ON x.account_id = p.account_id""";

    private final JdbcTemplate jdbc;

    public CustomerDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Customer> customers() {
        return jdbc.query(SELECT + " ORDER BY (p.kyc_review IS NULL), p.updated_at DESC NULLS LAST, p.account_id",
                (rs, i) -> map(rs));
    }

    public Optional<Customer> customer(String accountId) {
        return jdbc.query(SELECT + " WHERE p.account_id = ?", (rs, i) -> map(rs), accountId).stream().findFirst();
    }

    /** Locks the profile row until the transaction ends: KYC changes to one customer are serialised. */
    Optional<Customer> lock(String accountId) {
        jdbc.query("SELECT 1 FROM account_profile WHERE account_id = ? FOR UPDATE", (rs, i) -> 1, accountId);
        return customer(accountId);
    }

    Optional<Pii> pii(String accountId) {
        return jdbc.query("SELECT * FROM customer_pii WHERE account_id = ?", (rs, i) -> new Pii(
                rs.getString("full_name"), rs.getString("phone"), rs.getString("national_id"),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getObject("erased_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("erased_at", OffsetDateTime.class).toInstant()), accountId).stream().findFirst();
    }

    void register(String accountId, Pii pii, Instant now) {
        jdbc.update("""
                INSERT INTO account_profile (account_id, display_name, kyc_status, kyc_tier, registered_at, updated_at)
                VALUES (?, ?, 'NONE', 0, ?, ?)""", accountId, pii.fullName(), utc(now), utc(now));
        jdbc.update("""
                INSERT INTO customer_pii (account_id, full_name, phone, national_id, date_of_birth)
                VALUES (?, ?, ?, ?, ?)""", accountId, pii.fullName(), pii.phone(), pii.nationalId(), pii.dateOfBirth());
    }

    void updateKyc(String accountId, String status, int tier, UUID caseId, Integer tierRequested, String review,
            String note, Instant now) {
        jdbc.update("""
                UPDATE account_profile SET kyc_status = ?, kyc_tier = ?, kyc_case_id = ?, kyc_tier_requested = ?,
                       kyc_review = ?, kyc_note = ?, updated_at = ?
                WHERE account_id = ?""", status, tier, caseId, tierRequested, review, note, utc(now), accountId);
    }

    void erase(String accountId, Instant now) {
        jdbc.update("""
                UPDATE customer_pii SET full_name = NULL, phone = NULL, national_id = NULL, date_of_birth = NULL,
                       erased_at = ? WHERE account_id = ?""", utc(now), accountId);
        jdbc.update("UPDATE account_profile SET display_name = ?, updated_at = ? WHERE account_id = ?",
                "Erased customer", utc(now), accountId);
    }

    private static Customer map(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime registered = rs.getObject("registered_at", OffsetDateTime.class);
        OffsetDateTime updated = rs.getObject("updated_at", OffsetDateTime.class);
        return new Customer(rs.getString("account_id"), rs.getString("display_name"), rs.getString("kyc_status"),
                rs.getInt("kyc_tier"), rs.getObject("kyc_case_id", UUID.class),
                rs.getObject("kyc_tier_requested", Integer.class), rs.getString("kyc_review"),
                rs.getString("kyc_note"), mask(rs.getString("phone"), 3), mask(rs.getString("national_id"), 2),
                rs.getObject("erased_at", OffsetDateTime.class) != null,
                registered == null ? null : registered.toInstant(), updated == null ? null : updated.toInstant());
    }

    /** Keeps the last {@code keep} characters. */
    static String mask(String value, int keep) {
        if (value == null) {
            return null;
        }
        int hidden = Math.max(0, value.length() - keep);
        return "•".repeat(hidden) + value.substring(hidden);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
