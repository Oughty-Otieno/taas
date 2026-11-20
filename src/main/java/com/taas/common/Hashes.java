package com.taas.common;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Hashes {

    private Hashes() {
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String text) {
        return hex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Compares two hex digests in constant time. */
    public static boolean sameDigest(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static byte[] unhex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    /**
     * Unambiguous text form of a field map: keys in sorted order, every key and value length-prefixed, so no
     * choice of values can make two different maps serialise the same way.
     */
    public static String canonical(SortedMap<String, String> fields) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            out.append(e.getKey().length()).append(':').append(e.getKey())
                    .append(e.getValue().length()).append(':').append(e.getValue()).append(';');
        }
        return out.toString();
    }
}
