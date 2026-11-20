package com.taas.api;

import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import com.taas.common.InvalidRequest;
import com.taas.workflow.CommandRunner.Caller;
import com.taas.workflow.Reply;

/** Glue between HTTP and the engine: who the caller is, whom they may act for, and how a reply is sent. */
final class Http {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAY_HEADER = "Idempotent-Replay";
    /** Set on a customer's token: the one account they may act for. */
    static final String ACCOUNT_CLAIM = "account_id";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:\\-]{1,100}");

    private Http() {
    }

    /** The idempotency key is scoped to the client the access token was issued to. */
    static Caller caller(Jwt jwt, String idempotencyKey) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidRequest("Idempotency-Key header is required: 1-100 of A-Z, a-z, 0-9, '.', '_', ':', '-'");
        }
        return new Caller(jwt.getSubject(), idempotencyKey, boundAccount(jwt));
    }

    static String boundAccount(Jwt jwt) {
        return jwt == null ? null : jwt.getClaimAsString(ACCOUNT_CLAIM);
    }

    /** Reads: a customer sees their own account only. */
    static void requireOwn(Jwt jwt, String accountId) {
        String bound = boundAccount(jwt);
        if (bound != null && !bound.equals(accountId)) {
            throw new AccessDenied("This sign-in may only see account " + bound);
        }
    }

    static ResponseEntity<Object> send(Reply reply) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(reply.status());
        if (reply.replayed()) {
            builder.header(REPLAY_HEADER, "true");
        }
        return builder.body(reply.body());
    }

    /** A read the caller is not entitled to (403). */
    static class AccessDenied extends RuntimeException {
        AccessDenied(String message) {
            super(message);
        }
    }
}
