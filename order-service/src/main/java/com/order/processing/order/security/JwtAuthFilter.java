package com.order.processing.order.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Servlet-stack JWT authentication filter for the order-service.
 *
 * <h3>Two acceptance paths</h3>
 * <ol>
 *   <li><b>Gateway path</b>: The gateway has already validated the JWT and
 *       forwards three trusted headers:
 *       <ul>
 *         <li>{@code X-User-Id}   — numeric user ID from the JWT {@code uid} claim</li>
 *         <li>{@code X-Auth-User} — username from the JWT {@code sub} claim</li>
 *         <li>{@code X-Auth-Roles} — comma-separated roles</li>
 *       </ul>
 *       The filter builds an {@link AuthenticatedUser} from these headers and
 *       stores it as the Spring Security principal — <em>no JJWT parse</em>.</li>
 *
 *   <li><b>Direct path</b>: A caller presents a raw {@code Authorization: Bearer}
 *       header directly to the service.  The filter verifies the token with the
 *       shared HS256 secret, extracts the {@code uid} and {@code sub} claims, and
 *       builds the {@link AuthenticatedUser} from them.</li>
 * </ol>
 *
 * <p>In both cases the principal is an {@link AuthenticatedUser} record.  The
 * controller receives it via {@code @AuthenticationPrincipal AuthenticatedUser}.
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        this.signingKey = Keys.hmacShaKeyFor(
                jwtSecret.getBytes(StandardCharsets.UTF_8));
        log.info("[OrderService] JwtAuthFilter initialised");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // ── Path 1: trust gateway-forwarded identity headers ────────────────
        // The gateway only sets X-Auth-User after successful JWT validation,
        // so its presence is sufficient to trust the entire header set.
        String gatewayUser   = request.getHeader("X-Auth-User");
        String userIdHeader  = request.getHeader("X-User-Id");

        if (gatewayUser != null && !gatewayUser.isBlank()) {
            if (userIdHeader == null || userIdHeader.isBlank()) {
                // Gateway should always set X-User-Id — treat absence as a config error
                log.warn("[OrderService] X-Auth-User present but X-User-Id missing for path: {}",
                        request.getRequestURI());
                sendUnauthorized(response, "Missing X-User-Id header");
                return;
            }
            try {
                AuthenticatedUser principal =
                        AuthenticatedUser.fromHeaders(userIdHeader, gatewayUser);
                String rolesHeader = request.getHeader("X-Auth-Roles");
                setAuthentication(principal, parseRolesHeader(rolesHeader));
                log.debug("[OrderService] Authenticated via gateway headers: user='{}', userId={}",
                        gatewayUser, principal.userId());
            } catch (NumberFormatException ex) {
                log.warn("[OrderService] Malformed X-User-Id '{}': {}", userIdHeader, ex.getMessage());
                sendUnauthorized(response, "Malformed X-User-Id header");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // ── Path 2: validate raw Bearer token ───────────────────────────────
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No credentials — SecurityConfig will reject protected paths
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        Optional<Claims> claimsOpt = parseClaims(token);

        if (claimsOpt.isEmpty()) {
            log.warn("[OrderService] Invalid JWT for path: {}", request.getRequestURI());
            sendUnauthorized(response, "Invalid or expired token");
            return;
        }

        Claims claims  = claimsOpt.get();
        String subject = claims.getSubject();

        // Extract numeric user ID from the "uid" claim
        Object uidClaim = claims.get("uid");
        if (!(uidClaim instanceof Number)) {
            log.warn("[OrderService] JWT missing or invalid 'uid' claim for path: {}",
                    request.getRequestURI());
            sendUnauthorized(response, "Token does not contain a valid user ID");
            return;
        }
        Long userId = ((Number) uidClaim).longValue();

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class) != null
                ? claims.get("roles", List.class)
                : Collections.emptyList();

        AuthenticatedUser principal = new AuthenticatedUser(userId, subject);
        setAuthentication(principal, roles.stream()
                .map(SimpleGrantedAuthority::new)
                .toList());

        log.debug("[OrderService] Authenticated via Bearer JWT: user='{}', userId={}", subject, userId);
        filterChain.doFilter(request, response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Optional<Claims> parseClaims(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("[OrderService] JWT parse failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private List<SimpleGrantedAuthority> parseRolesHeader(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) return Collections.emptyList();
        return java.util.Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    private void setAuthentication(AuthenticatedUser principal,
                                   List<SimpleGrantedAuthority> authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
