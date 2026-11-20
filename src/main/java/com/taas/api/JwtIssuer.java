package com.taas.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Mints access tokens for local development and the demo. A deployment would use its identity provider (any
 * OAuth2 authorisation server issuing scoped tokens) and switch the decoder to its JWKS; nothing else changes.
 */
@Component
public class JwtIssuer {

    private final NimbusJwtEncoder encoder;
    private final Clock clock;

    public JwtIssuer(SecretKey jwtKey, Clock clock) {
        this.encoder = NimbusJwtEncoder.withSecretKey(jwtKey).algorithm(MacAlgorithm.HS256).build();
        this.clock = clock;
    }

    public String issue(String clientId, Collection<String> scopes, Duration ttl) {
        return issue(clientId, scopes, ttl, Map.of());
    }

    public String issue(String subject, Collection<String> scopes, Duration ttl, Map<String, Object> extraClaims) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("scope", String.join(" ", scopes));
        extraClaims.forEach(builder::claim);
        JwtClaimsSet claims = builder.build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
