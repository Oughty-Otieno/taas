package com.taas.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.taas.common.Rejection;
import com.taas.ledger.ActionLedger;
import com.taas.workflow.CommandRunner.Caller;

/**
 * Decisions that are not invariants: may this caller act for this account, is this customer eligible, is this
 * account moving too fast. Asked before a workflow starts (a deny never reaches it); the engine re-checks inside
 * the transaction. A deny is recorded in the Action Ledger like any other rejection.
 *
 * <p>KYC is tiered: tier 1 (identity verified) may hold assets and buy up to a limit per purchase; tier 2
 * (address and source of funds verified) has no limit. A FROZEN account keeps its units but cannot buy, send or
 * receive. The velocity counter reads the Action Ledger; a production deployment would keep it in Redis.
 */
@Component
public class PolicyDecisionPoint {

    public record Profile(String accountId, String displayName, String kycStatus, int kycTier, UUID kycCaseId,
            Integer kycTierRequested, String kycReview, String kycNote) {
    }

    private final JdbcTemplate jdbc;
    private final ActionLedger actions;
    private final Clock clock;
    private final int maxTransfersPerHour;
    private final long tier1LimitKes;

    public PolicyDecisionPoint(JdbcTemplate jdbc, ActionLedger actions, Clock clock,
            @Value("${taas.policy.max-transfers-per-hour}") int maxTransfersPerHour,
            @Value("${taas.kyc.tier1-limit-kes}") long tier1LimitKes) {
        this.jdbc = jdbc;
        this.actions = actions;
        this.clock = clock;
        this.maxTransfersPerHour = maxTransfersPerHour;
        this.tier1LimitKes = tier1LimitKes;
    }

    public Optional<Profile> profile(String accountId) {
        return jdbc.query("SELECT * FROM account_profile WHERE account_id = ?", this::map, accountId).stream()
                .findFirst();
    }

    public List<Profile> profiles() {
        return jdbc.query("SELECT * FROM account_profile ORDER BY account_id", this::map);
    }

    /**
     * Authorisation: a customer's token is bound to one account and may act for it alone. A scope says what
     * kind of thing a caller may do; this says on whose behalf. Staff and partner systems are not bound.
     */
    public void requireActingFor(Caller caller, String accountId) {
        if (caller.boundAccount() != null && !caller.boundAccount().equals(accountId)) {
            throw new Rejection(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_PERMITTED", caller.clientId()
                    + " may act only for account " + caller.boundAccount() + ", not " + accountId);
        }
    }

    /** Eligibility to hold or buy an asset token worth {@code amountKes}. */
    public void requireKyc(String accountId, String tokenId, long amountKes) {
        Profile p = profile(accountId).orElse(null);
        String status = p == null ? "NONE" : p.kycStatus();
        if ("FROZEN".equals(status)) {
            throw frozen(accountId);
        }
        if (!"VERIFIED".equals(status)) {
            throw new Rejection(HttpStatus.FORBIDDEN, "KYC_REQUIRED",
                    accountId + " must have verified KYC to hold " + tokenId + " (KYC status: " + status + ")");
        }
        if (p.kycTier() < 2 && amountKes > tier1LimitKes) {
            throw new Rejection(HttpStatus.FORBIDDEN, "KYC_TIER_TOO_LOW", accountId + " is verified at tier "
                    + p.kycTier() + ", which allows up to KES " + tier1LimitKes + " per purchase; this one is KES "
                    + amountKes + ". Tier 2 KYC is needed");
        }
    }

    /** A frozen account cannot move units out. */
    public void requireNotFrozen(String accountId) {
        if (profile(accountId).map(p -> "FROZEN".equals(p.kycStatus())).orElse(false)) {
            throw frozen(accountId);
        }
    }

    /** Velocity: at most N transfers out of an account per rolling hour. */
    public void requireWithinVelocity(String accountId) {
        long recent = actions.transfersFrom(accountId, clock.instant().minus(Duration.ofHours(1)));
        if (recent >= maxTransfersPerHour) {
            throw new Rejection(HttpStatus.TOO_MANY_REQUESTS, "VELOCITY_LIMIT", accountId + " has made " + recent
                    + " transfers in the last hour; the limit is " + maxTransfersPerHour);
        }
    }

    public int maxTransfersPerHour() {
        return maxTransfersPerHour;
    }

    public long tier1LimitKes() {
        return tier1LimitKes;
    }

    private static Rejection frozen(String accountId) {
        return new Rejection(HttpStatus.FORBIDDEN, "ACCOUNT_FROZEN",
                accountId + " is frozen pending KYC review: it keeps its units but cannot buy, send or receive");
    }

    private Profile map(ResultSet rs, int i) throws SQLException {
        return new Profile(rs.getString("account_id"), rs.getString("display_name"), rs.getString("kyc_status"),
                rs.getInt("kyc_tier"), rs.getObject("kyc_case_id", UUID.class),
                rs.getObject("kyc_tier_requested", Integer.class), rs.getString("kyc_review"),
                rs.getString("kyc_note"));
    }
}
