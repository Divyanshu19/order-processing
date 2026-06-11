package com.order.processing.auth.controller;

import com.order.processing.auth.dto.LoginRequest;
import com.order.processing.auth.dto.LoginResponse;
import com.order.processing.auth.security.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Handles authentication requests.
 *
 * <h3>Endpoint</h3>
 * <pre>
 *   POST /auth/login
 *   Content-Type: application/json
 *
 *   { "username": "admin", "password": "secret" }
 *
 *   → 200 OK
 *   { "token": "&lt;JWT&gt;", "type": "Bearer", "expiresIn": 3600 }
 *
 *   → 401 Unauthorized  (bad credentials)
 * </pre>
 *
 * <p>Credentials are checked against a compile-time hardcoded map (two users).
 * Suitable for demonstrating JWT issuance; replace with a real
 * {@code UserDetailsService} for production use.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.expiration-ms:3600000}")
    private long jwtExpirationMs;

    /**
     * Hardcoded user store: username → UserEntry.
     *
     * <p>Each entry carries an explicit numeric {@code id} that is embedded in
     * the JWT as the {@code uid} claim.  Downstream services (e.g. order-service)
     * read that claim to set the owner of a resource, never trusting a
     * client-supplied value in the request body.
     *
     * <p>In production this would delegate to a {@code UserDetailsService}
     * backed by a database or LDAP directory where the user's PK is authoritative.
     */
    private static final Map<String, UserEntry> USERS = Map.of(
            "admin", new UserEntry(1L, "secret",      List.of("ROLE_ADMIN", "ROLE_USER")),
            "user",  new UserEntry(2L, "userpassword", List.of("ROLE_USER"))
    );

    /**
     * Issues a signed JWT for valid credentials.
     *
     * <p>The token payload contains:
     * <ul>
     *   <li>{@code sub}   — username</li>
     *   <li>{@code uid}   — numeric user ID (authoritative; never trust the request body)</li>
     *   <li>{@code roles} — granted authorities</li>
     * </ul>
     *
     * @param request login payload (username + password)
     * @return {@code 200 OK} with JWT, or {@code 401 Unauthorized}
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        log.info("[AuthService] Login attempt for user '{}'", request.getUsername());

        UserEntry entry = USERS.get(request.getUsername());

        if (entry == null || !entry.password().equals(request.getPassword())) {
            log.warn("[AuthService] Login failed for user '{}'", request.getUsername());
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        String token = jwtTokenProvider.generateToken(
                request.getUsername(), entry.id(), entry.roles());
        long expiresInSeconds = jwtExpirationMs / 1000;

        log.info("[AuthService] Login successful – user='{}', id={}, roles={}",
                request.getUsername(), entry.id(), entry.roles());

        return ResponseEntity.ok(new LoginResponse(token, "Bearer", expiresInSeconds));
    }

    // ── Inner record — replaces a separate UserDetails class for this demo ──

    /**
     * @param id       numeric primary key embedded as JWT {@code uid} claim
     * @param password plaintext password (BCrypt in production)
     * @param roles    granted authorities
     */
    private record UserEntry(Long id, String password, List<String> roles) {}
}
