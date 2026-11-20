package com.taas.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Authenticates a provider's webhook: {@code signature = hex(HMAC-SHA256(secret, timestamp + "." + body))}, with
 * the timestamp sent alongside. A stale timestamp is refused, so a captured callback cannot be replayed later; a
 * redelivery inside the window is absorbed by the provider's event id (the idempotency key).
 */
public class CallbackSignature {

    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private final String secret;
    private final Clock clock;

    public CallbackSignature(String secret, Clock clock) {
        this.secret = secret;
        this.clock = clock;
    }

    public String sign(long timestamp, String body) {
        return Hashes.hmacSha256Hex(secret, timestamp + "." + body);
    }

    public boolean verify(String timestamp, String signature, String body) {
        if (timestamp == null || signature == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(ts), clock.instant()).abs().compareTo(MAX_SKEW) > 0) {
            return false;
        }
        return Hashes.sameDigest(sign(ts, body), signature);
    }
}
