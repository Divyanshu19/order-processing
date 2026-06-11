package com.order.processing.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Issues and validates HMAC-SHA256 (HS256) JSON Web Tokens.
 *
 * <h3>Token anatomy</h3>
 * <pre>
 *   Header  : { "alg": "HS256", "typ": "JWT" }
 *   Payload : { "sub": "&lt;username&gt;",
 *               "roles": ["ROLE_USER"],
 *               "iat": &lt;epoch-seconds&gt;,
 *               "exp": &lt;epoch-seconds&gt; }
 *   Signature: HMAC-SHA256(base64url(header) + "." + base64url(payload), secretKey)
 * </pre>
 *
 * <p>The signing key is read from {@code jwt.secret} in {@code application.yml}.
 * It must be at least 32 characters long (256 bits) so that HS256 accepts it.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /** Signing secret – must be ≥ 32 chars.  Override via env-var JWT_SECRET. */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /** Token validity in milliseconds.  Default: 1 hour. */
    @Value("${jwt.expiration-ms:3600000}")
    private long jwtExpirationMs;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("[AuthService] JwtTokenProvider initialised – expiration {}ms", jwtExpirationMs);
    }

    /**
     * Builds and signs a JWT for the supplied user.
     *
     * <h3>Claims</h3>
     * <ul>
     *   <li>{@code sub}   — username (human-readable identity)</li>
     *   <li>{@code uid}   — numeric user ID (authoritative DB primary key)</li>
     *   <li>{@code roles} — list of granted authorities</li>
     *   <li>{@code iat}   — issued-at epoch seconds</li>
     *   <li>{@code exp}   — expiry epoch seconds</li>
     * </ul>
     *
     * @param username  authenticated user's name (becomes the {@code sub} claim)
     * @param userId    the numeric database primary key for this user
     * @param roles     list of authority strings, e.g. {@code ["ROLE_USER"]}
     * @return compact, URL-safe JWT string
     */
    public String generateToken(String username, Long userId, List<String> roles) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("uid",   userId)   // numeric user ID — used by order-service
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)     // algorithm inferred from key type (HS256)
                .compact();
    }

    /**
     * Parses and validates a JWT string.
     *
     * @param token compact JWT
     * @return the subject ({@code sub}) claim
     * @throws io.jsonwebtoken.JwtException if the token is invalid or expired
     */
    public String getSubject(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Returns {@code true} if the token can be parsed and has not expired.
     */
    public boolean isValid(String token) {
        try {
            getSubject(token);
            return true;
        } catch (Exception ex) {
            log.debug("[AuthService] Token validation failed: {}", ex.getMessage());
            return false;
        }
    }
}
