package com.taas.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.SortedMap;

public final class Hashes {

    private Hashes() {
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Unambiguous text form of a payload: keys in sorted order, every key and value length-prefixed so no
     * choice of values can make two different payloads serialise the same way.
     */
    public static String canonical(SortedMap<String, String> fields) {
        StringBuilder out = new StringBuilder();
        fields.forEach((key, value) -> out.append(key.length()).append(':').append(key)
                .append(value.length()).append(':').append(value).append(';'));
        return out.toString();
    }
}
