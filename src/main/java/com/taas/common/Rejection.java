package com.taas.common;

import org.springframework.http.HttpStatus;

/**
 * A request the platform refuses for a business reason: a rule, a policy decision or a database invariant.
 * Rejections are recorded in the Action Ledger as {@code REQUEST_REJECTED}, and the reply is stored against
 * the idempotency key, so retrying the same request gets the same answer.
 */
public class Rejection extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public Rejection(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static Rejection rule(String code, String message) {
        return new Rejection(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public static Rejection conflict(String code, String message) {
        return new Rejection(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
