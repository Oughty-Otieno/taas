package com.taas.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The gateway check in front of every /api route: callers present an API key in {@code X-API-Key}.
 * Keys are compared in constant time. A real deployment would put this in an API gateway with per-client
 * keys, scopes and rate limits; this filter is the stand-in.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final List<byte[]> keys;

    public ApiKeyFilter(@Value("${taas.api-keys}") String keys) {
        this.keys = Arrays.stream(keys.split(","))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .map(k -> k.getBytes(StandardCharsets.UTF_8))
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && isKnown(presented.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Missing or unknown " + HEADER + "\"}");
    }

    private boolean isKnown(byte[] presented) {
        boolean match = false;
        for (byte[] key : keys) {
            match |= MessageDigest.isEqual(key, presented);
        }
        return match;
    }
}
