package com.taas.common;

/** The Action Ledger's hash chain does not verify: history was altered outside the platform. */
public class LedgerIntegrityException extends RuntimeException {
    public LedgerIntegrityException(String message) {
        super(message);
    }
}
