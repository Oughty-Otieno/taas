package com.taas.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The gateway check: every call carries {@code Authorization: Bearer <access token>}, the token's signature,
 * issuer and expiry are validated, and its scope is what each route requires. The PSP callback is the exception:
 * it is authenticated by its HMAC signature (mTLS in production), not OAuth.
 */
@Configuration
public class SecurityConfig {

    public static final String ISSUER = "taas";

    @Bean
    SecretKey jwtKey(@Value("${taas.auth.jwt-secret}") String secret) {
        if (secret.length() < 32) {
            throw new IllegalStateException("taas.auth.jwt-secret must be at least 32 characters");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER)));
        return decoder;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, @Value("${taas.demo-endpoints}") boolean demo) throws Exception {
        // EventSource cannot set headers, so the live stream may pass the token as ?access_token=.
        DefaultBearerTokenResolver tokens = new DefaultBearerTokenResolver();
        tokens.setAllowUriQueryParameter(true);

        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                    a.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                    a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    a.requestMatchers("/actuator/health").permitAll();
                    a.requestMatchers(HttpMethod.GET, "/", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs",
                            "/v3/api-docs/**").permitAll();
                    a.requestMatchers(HttpMethod.POST, "/v1/payments/callback", "/v1/kyc/callback").permitAll();
                    if (demo) {
                        a.requestMatchers(HttpMethod.POST, "/v1/dev/token").permitAll();
                        a.requestMatchers(HttpMethod.GET, "/v1/dev/personas").permitAll();
                        a.requestMatchers("/v1/dev/**").hasAuthority("SCOPE_ledger:admin");
                    }
                    a.requestMatchers(HttpMethod.GET, "/v1/tokens/**", "/v1/accounts/**").hasAuthority("SCOPE_token:read");
                    a.requestMatchers(HttpMethod.POST, "/v1/tokens", "/v1/issuances").hasAuthority("SCOPE_token:issue");
                    a.requestMatchers(HttpMethod.POST, "/v1/awards").hasAuthority("SCOPE_token:award");
                    a.requestMatchers("/v1/reservations/**", "/v1/orders/**").hasAuthority("SCOPE_token:trade");
                    a.requestMatchers(HttpMethod.POST, "/v1/transfers").hasAuthority("SCOPE_token:transfer");
                    a.requestMatchers(HttpMethod.POST, "/v1/redemptions").hasAuthority("SCOPE_token:redeem");
                    a.requestMatchers(HttpMethod.POST, "/v1/ledger/replay", "/v1/ledger/wipe")
                            .hasAuthority("SCOPE_ledger:admin");
                    a.requestMatchers(HttpMethod.GET, "/v1/ledger/**").hasAuthority("SCOPE_ledger:read");
                    a.requestMatchers(HttpMethod.GET, "/v1/customers", "/v1/customers/**")
                            .hasAnyAuthority("SCOPE_customer:read", "SCOPE_kyc:submit");
                    a.requestMatchers(HttpMethod.POST, "/v1/customers").hasAuthority("SCOPE_customer:register");
                    a.requestMatchers(HttpMethod.POST, "/v1/customers/*/kyc").hasAuthority("SCOPE_kyc:submit");
                    a.requestMatchers(HttpMethod.POST, "/v1/customers/*/kyc/decision").hasAuthority("SCOPE_kyc:review");
                    a.requestMatchers(HttpMethod.DELETE, "/v1/customers/*/pii").hasAuthority("SCOPE_kyc:review");
                    a.requestMatchers(HttpMethod.POST, "/v1/rules/versions").hasAuthority("SCOPE_rules:publish");
                    a.requestMatchers(HttpMethod.GET, "/v1/rules/**").hasAuthority("SCOPE_rules:read");
                    a.requestMatchers(HttpMethod.POST, "/v1/rules/simulate", "/v1/rules/evaluate").hasAuthority("SCOPE_rules:read");
                    a.anyRequest().denyAll();
                })
                .oauth2ResourceServer(o -> o.bearerTokenResolver(tokens).jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((req, res, e) -> problem(res, 401, "Unauthorized",
                                "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, e) -> problem(res, 403, "Forbidden",
                                "The access token lacks the scope this route requires")))
                .exceptionHandling(x -> x
                        .authenticationEntryPoint((req, res, e) -> problem(res, 401, "Unauthorized",
                                "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, e) -> problem(res, 403, "Forbidden",
                                "The access token lacks the scope this route requires")));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${taas.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("Idempotent-Replay"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", cors);
        return source;
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse res, int status, String title,
            String detail) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
