package com.order.processing.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Stateless JWT validator used by the gateway to verify incoming Bearer tokens.
 *
 * <p>The gateway does NOT issue tokens — it only verifies them.
 * The signing secret MUST be the same value used by {@code auth-service}.
 * Both services read it from the {@code JWT_SECRET} environment variable
 * (or the default in {@code application.yml} for local dev).
 *
 * <h3>Single-parse design</h3>
 * All public methods delegate to {@link #parseClaims(String)}, which performs
 * exactly one JJWT parse per call.  Callers that need several claims should
 * call {@code parseClaims()} once and work with the returned {@link Claims}.
 */
@Slf4j
@Component
public class JwtTokenValidator {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("[Gateway] JwtTokenValidator initialised");
    }

    /**
     * Parses the compact JWT string and returns its verified {@link Claims}.
     *
     * @param token compact JWT (without "Bearer " prefix)
     * @return {@link Optional} containing the claims if valid and not expired,
     *         or {@link Optional#empty()} if the token is invalid for any reason
     */
    public Optional<Claims> parseClaims(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("[Gateway] JWT validation failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Convenience wrapper: returns {@code true} if the token is well-formed,
     * correctly signed, and not expired.
     *
     * @param token compact JWT (without "Bearer " prefix)
     */
    public boolean isValid(String token) {
        return parseClaims(token).isPresent();
    }

    /**
     * Extracts the {@code sub} (subject / username) claim.
     *
     * @param token compact JWT (without "Bearer " prefix)
     * @return subject string, or {@link Optional#empty()} if token is invalid
     */
    public Optional<String> getSubject(String token) {
        return parseClaims(token).map(Claims::getSubject);
    }
}
