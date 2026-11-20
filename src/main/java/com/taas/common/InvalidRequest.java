package com.taas.common;

import java.util.List;

/** A malformed request (400). Not a business decision, so it is neither recorded nor stored. */
public class InvalidRequest extends RuntimeException {

    private final List<String> errors;

    public InvalidRequest(String message) {
        this(message, List.of());
    }

    public InvalidRequest(String message, List<String> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
