package com.taas.common;

/** The request is well formed but the workflow rules refuse it (e.g. insufficient balance). */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
