package com.taas.common;

/** A transaction id was reused for a different request. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String txId) {
        super("Transaction id '" + txId + "' was already used for a different request");
    }
}
