package com.taas.ledger;

/** Everything that can happen to a token. Each type has exactly one posting rule in {@link PostingRules}. */
public enum EventType {
    TOKEN_CREATED,
    ISSUED,
    EARNED,
    TRANSFERRED,
    RESERVED,
    PURCHASED,
    RESERVATION_RELEASED,
    RESERVATION_EXPIRED,
    REDEEMED
}
