package com.taas.workflow;

/**
 * What a command answers: an HTTP status and a body. The same reply is stored against the idempotency key and
 * returned unchanged to a retry ({@code replayed} is then true).
 */
public record Reply(int status, Object body, boolean replayed) {

    public static Reply of(int status, Object body) {
        return new Reply(status, body, false);
    }
}
