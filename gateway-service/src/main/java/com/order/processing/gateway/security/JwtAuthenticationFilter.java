package com.order.processing.gateway.security;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Reactive {@link WebFilter} that converts a valid Bearer JWT into a Spring
 * Security {@link Authentication} stored in the reactive security context.
 *
 * <h3>Responsibility split</h3>
 * <ul>
 *   <li><b>This filter</b>: extract the token → parse claims once → populate
 *       {@link ReactiveSecurityContextHolder} + mutate request headers.</li>
 *   <li><b>{@link SecurityConfig}</b>: define which paths require an
 *       authenticated principal and which are public.  Spring Security then
 *       rejects unauthenticated requests with {@code 401} automatically.</li>
 * </ul>
 *
 * <h3>Header propagation</h3>
 * Two headers are added to every forwarded request so that downstream services
 * can trust the caller's identity without re-parsing the JWT:
 * <ul>
 *   <li>{@code X-Auth-User}  — the {@code sub} (username) claim</li>
 *   <li>{@code X-Auth-Roles} — comma-separated roles from the {@code roles} claim</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtTokenValidator jwtTokenValidator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        // No Bearer header → proceed without authentication.
        // SecurityConfig will reject the request if the path requires auth.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(7).trim(); // strip "Bearer " + any surrounding whitespace

        // Reject a structurally empty Bearer value immediately (e.g. "Bearer " with nothing after)
        if (token.isEmpty()) {
            log.warn("[Gateway] Empty Bearer token for path: {}",
                    exchange.getRequest().getURI().getPath());
            return writeUnauthorized(exchange.getResponse());
        }

        // Parse claims exactly once
        Optional<Claims> claimsOpt = jwtTokenValidator.parseClaims(token);

        if (claimsOpt.isEmpty()) {
            log.warn("[Gateway] Invalid or expired JWT for path: {}",
                    exchange.getRequest().getURI().getPath());
            // Token was presented but is invalid — reject immediately with 401.
            // Do NOT fall through: the client explicitly sent a Bearer token so
            // it knows the scheme, and an invalid token should never reach downstream.
            return writeUnauthorized(exchange.getResponse());
        }

        Claims claims  = claimsOpt.get();
        String subject = claims.getSubject();

        // Extract roles claim and convert to GrantedAuthority list
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class) != null
                ? claims.get("roles", List.class)
                : Collections.emptyList();

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(subject, null, authorities);

        // Extract the numeric user ID claim.
        // JJWT deserialises JSON integers as Integer by default (fits in 32 bits
        // for realistic user IDs), but we guard with a Number cast for safety.
        String userId = "";
        Object uidClaim = claims.get("uid");
        if (uidClaim instanceof Number n) {
            userId = String.valueOf(n.longValue());
        }

        log.debug("[Gateway] JWT valid – user='{}', userId={}, roles={}, path={}",
                subject, userId, roles, exchange.getRequest().getURI().getPath());

        // Mutate the forwarded request to carry the three identity headers
        String rolesHeader  = String.join(",", roles);
        final String userIdHeader = userId;
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r
                        .header("X-Auth-User",  subject)
                        .header("X-Auth-Roles", rolesHeader)
                        .header("X-User-Id",    userIdHeader))
                .build();

        // Store authentication in the reactive security context, then continue
        return chain.filter(mutated)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    /**
     * Writes a {@code 401 Unauthorized} JSON response directly to the client and
     * completes the exchange without forwarding to the next filter.
     *
     * <p>Used when a Bearer token is present in the request but is invalid or empty,
     * so the filter short-circuits with a machine-readable error immediately at the
     * gateway rather than letting the unauthenticated request reach downstream services.
     */
    private Mono<Void> writeUnauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"Invalid or expired token\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
