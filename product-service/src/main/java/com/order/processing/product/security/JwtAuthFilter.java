package com.order.processing.product.security;

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
 * Servlet-stack JWT authentication filter for the product-service.
 *
 * <p>Extends {@link OncePerRequestFilter} — guaranteed exactly one execution
 * per request regardless of forward/include dispatches.
 *
 * <h3>Two acceptance paths</h3>
 * <ol>
 *   <li><b>Gateway path</b>: The gateway has already validated the token and
 *       forwards {@code X-Auth-User} + {@code X-Auth-Roles} headers. The
 *       filter trusts these headers and builds the security context from them,
 *       skipping the JJWT parse entirely.</li>
 *   <li><b>Direct path</b>: A caller bypasses the gateway and presents a raw
 *       {@code Authorization: Bearer &lt;token&gt;} header. The filter parses
 *       and verifies the token with the shared HS256 secret.</li>
 * </ol>
 *
 * <p>If neither path yields a valid identity the filter chain continues without
 * setting a principal, and Spring Security rejects the request with {@code 401}
 * based on the rules in {@link SecurityConfig}.
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
        log.info("[ProductService] JwtAuthFilter initialised");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // ── Path 1: trust gateway-forwarded identity headers ────────────────
        String gatewayUser = request.getHeader("X-Auth-User");
        if (gatewayUser != null && !gatewayUser.isBlank()) {
            String rolesHeader = request.getHeader("X-Auth-Roles");
            List<SimpleGrantedAuthority> authorities = parseRolesHeader(rolesHeader);

            setAuthentication(gatewayUser, authorities);
            log.debug("[ProductService] Authenticated via gateway headers: user='{}'", gatewayUser);
            filterChain.doFilter(request, response);
            return;
        }

        // ── Path 2: validate raw Bearer token ───────────────────────────────
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No credentials at all — let Spring Security's rules decide
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        Optional<Claims> claimsOpt = parseClaims(token);
        if (claimsOpt.isEmpty()) {
            log.warn("[ProductService] Invalid JWT for path: {}", request.getRequestURI());
            sendUnauthorized(response, "Invalid or expired token");
            return;
        }

        Claims claims  = claimsOpt.get();
        String subject = claims.getSubject();

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class) != null
                ? claims.get("roles", List.class)
                : Collections.emptyList();

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        setAuthentication(subject, authorities);
        log.debug("[ProductService] Authenticated via Bearer JWT: user='{}'", subject);
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
            log.debug("[ProductService] JWT parse failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private List<SimpleGrantedAuthority> parseRolesHeader(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return Collections.emptyList();
        }
        return java.util.Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    private void setAuthentication(String subject, List<SimpleGrantedAuthority> authorities) {
        var auth = new UsernamePasswordAuthenticationToken(subject, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
