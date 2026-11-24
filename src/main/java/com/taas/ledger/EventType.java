package com.taas.ledger;

/**
 * Everything that can be recorded in the Action Ledger. Each type has exactly one movement rule in
 * {@link MovementRules}; types with no movements record a decision (a payment, a rejection, a rule change).
 */
public enum EventType {
    TOKEN_DEFINED(Outcome.OK),
    TOKEN_ISSUED(Outcome.OK),
    TOKEN_EARNED(Outcome.OK),
    AWARD_NOT_QUALIFIED(Outcome.DECLINED),
    TOKEN_RESERVED(Outcome.OK),
    TOKEN_RESERVATION_RELEASED(Outcome.REVERSED),
    TOKEN_RESERVATION_EXPIRED(Outcome.REVERSED),
    ORDER_PLACED(Outcome.OK),
    PAYMENT_RECEIVED(Outcome.OK),
    PAYMENT_FAILED(Outcome.FAILED),
    PAYMENT_REFUND_REQUIRED(Outcome.FAILED),
    TOKEN_PURCHASED(Outcome.OK),
    TOKEN_TRANSFERRED(Outcome.OK),
    TOKEN_REDEEMED(Outcome.OK),
    PAYOUT_SENT(Outcome.OK),
    PAYOUT_FAILED(Outcome.FAILED),
    TOKEN_REVOKED(Outcome.REVERSED),
    RULE_VERSION_PUBLISHED(Outcome.OK),
    CUSTOMER_REGISTERED(Outcome.OK),
    KYC_SUBMITTED(Outcome.OK),
    KYC_REFERRED(Outcome.DECLINED),
    KYC_VERIFIED(Outcome.OK),
    KYC_REJECTED(Outcome.FAILED),
    KYC_REVOKED(Outcome.REVERSED),
    PII_ERASED(Outcome.OK),
    BUSINESS_REGISTERED(Outcome.OK),
    BUSINESS_VERIFIED(Outcome.OK),
    BUSINESS_SUSPENDED(Outcome.REVERSED),
    TOKEN_ISSUER_ASSIGNED(Outcome.OK),
    STAFF_USER_CREATED(Outcome.OK),
    STAFF_ROLE_CHANGED(Outcome.OK),
    STAFF_USER_DISABLED(Outcome.REVERSED),
    STAFF_USER_ENABLED(Outcome.OK),
    REQUEST_REJECTED(Outcome.FAILED);

    /** How a screen should present the event: failures and reversals are shown differently from successes. */
    public enum Outcome { OK, DECLINED, REVERSED, FAILED }

    private final Outcome outcome;

    EventType(Outcome outcome) {
        this.outcome = outcome;
    }

    public Outcome outcome() {
        return outcome;
    }
}
