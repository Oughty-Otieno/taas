package com.taas.ledger;

import java.util.List;

/**
 * The single place that turns an event into token movements. Live writes and replay both go through here,
 * which is why replaying the Action Ledger always reproduces the same holdings.
 *
 * <p>Every rule balances: the legs of an event sum to zero, so units are never created or destroyed except at
 * the supply boundary ({@code @mint}) and the retirement account ({@code @burn}).
 *
 * <p>A reservation moves units out of the treasury into the <em>buyer's</em> RESERVED bucket: not yet theirs to
 * use, but counted toward their ownership cap, so a buyer cannot hold reservations that together exceed it.
 */
public final class MovementRules {

    public enum Bucket { AVAILABLE, RESERVED }

    public record Leg(String account, Bucket bucket, long units) {
    }

    private MovementRules() {
    }

    public static List<Leg> legsFor(ActionEvent e) {
        return switch (e.type()) {
            case TOKEN_DEFINED, AWARD_NOT_QUALIFIED, ORDER_PLACED, PAYMENT_RECEIVED, PAYMENT_REFUND_REQUIRED,
                    RULE_VERSION_PUBLISHED, REQUEST_REJECTED, CUSTOMER_REGISTERED, KYC_SUBMITTED, KYC_REFERRED,
                    KYC_VERIFIED, KYC_REJECTED, KYC_REVOKED, PII_ERASED, BUSINESS_REGISTERED, BUSINESS_VERIFIED,
                    BUSINESS_SUSPENDED, TOKEN_ISSUER_ASSIGNED, STAFF_USER_CREATED, STAFF_ROLE_CHANGED,
                    STAFF_USER_DISABLED, STAFF_USER_ENABLED, PAYOUT_SENT, PAYOUT_FAILED -> List.of();
            case TOKEN_ISSUED -> move(Accounts.MINT, Bucket.AVAILABLE, Accounts.TREASURY, Bucket.AVAILABLE, units(e));
            case TOKEN_EARNED -> move(Accounts.TREASURY, Bucket.AVAILABLE, e.get("accountId"), Bucket.AVAILABLE,
                    units(e));
            case TOKEN_RESERVED -> move(Accounts.TREASURY, Bucket.AVAILABLE, e.get("accountId"), Bucket.RESERVED,
                    units(e));
            case TOKEN_PURCHASED -> move(e.get("accountId"), Bucket.RESERVED, e.get("accountId"), Bucket.AVAILABLE,
                    units(e));
            case TOKEN_RESERVATION_RELEASED, TOKEN_RESERVATION_EXPIRED, PAYMENT_FAILED ->
                    move(e.get("accountId"), Bucket.RESERVED, Accounts.TREASURY, Bucket.AVAILABLE, units(e));
            case TOKEN_TRANSFERRED -> move(e.get("fromAccountId"), Bucket.AVAILABLE, e.get("toAccountId"),
                    Bucket.AVAILABLE, units(e));
            case TOKEN_REDEEMED -> move(e.get("accountId"), Bucket.AVAILABLE, Accounts.BURN, Bucket.AVAILABLE,
                    units(e));
            case TOKEN_REVOKED -> move(e.get("accountId"), Bucket.AVAILABLE, Accounts.TREASURY, Bucket.AVAILABLE,
                    units(e));
        };
    }

    private static long units(ActionEvent e) {
        return e.getLong("units");
    }

    private static List<Leg> move(String from, Bucket fromBucket, String to, Bucket toBucket, long units) {
        if (units <= 0) {
            throw new IllegalArgumentException("Movement units must be positive, was " + units);
        }
        return List.of(new Leg(from, fromBucket, -units), new Leg(to, toBucket, units));
    }
}
