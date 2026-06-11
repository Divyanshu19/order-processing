package com.order.processing.gateway.config;

import com.order.processing.gateway.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Reactive Spring Security configuration for the API Gateway.
 *
 * <h3>Authorization model</h3>
 * <ul>
 *   <li>{@code /auth/**}      — public (login route, forwarded to auth-service)</li>
 *   <li>{@code /actuator/**}  — public (gateway health/info endpoints)</li>
 *   <li>All other paths       — require an authenticated principal, which is
 *       populated by {@link JwtAuthenticationFilter} before this check runs</li>
 * </ul>
 *
 * <h3>Filter ordering</h3>
 * {@link JwtAuthenticationFilter} is inserted at
 * {@link SecurityWebFiltersOrder#AUTHENTICATION} so it fires after the
 * {@code SecurityContextServerWebExchangeWebFilter} (which sets up the
 * exchange) but before Spring Security's own authentication providers
 * (which there are none of — we handle auth ourselves).
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                // ── Register JWT filter before Spring's auth phase ──────────
                .addFilterAt(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)

                // ── Path-based authorization rules ──────────────────────────
                .authorizeExchange(exchanges -> exchanges
                        // Public: auth-service login (proxied through the gateway)
                        .pathMatchers("/auth/**").permitAll()
                        // Public: gateway's own actuator endpoints (new base-path)
                        .pathMatchers("/management/**").permitAll()
                        // Public: legacy actuator path kept for backward-compat
                        .pathMatchers("/actuator/**").permitAll()
                        // Everything else requires a valid JWT principal
                        .anyExchange().authenticated()
                )

                // ── Return JSON 401 instead of Spring's default redirect/403 ─
                // Both handlers must be set: authenticationEntryPoint fires for
                // AuthenticationException, accessDeniedHandler fires for
                // AccessDeniedException (anonymous users on protected paths).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jsonUnauthorizedEntryPoint())
                        .accessDeniedHandler(jsonAccessDeniedHandler())
                )

                .build();
    }

    /**
     * Returns a JSON {@code 401} body instead of Spring Security's default
     * HTML / redirect response, so API clients get a machine-readable error.
     * <p>Fires for {@link org.springframework.security.core.AuthenticationException}
     * (no principal at all — e.g. completely missing Authorization header when
     * anonymous auth is disabled).</p>
     */
    private ServerAuthenticationEntryPoint jsonUnauthorizedEntryPoint() {
        return (exchange, denied) -> json401Response(exchange.getResponse());
    }

    /**
     * Returns a JSON {@code 401} body for {@link org.springframework.security.access.AccessDeniedException}.
     * <p>In Spring Security WebFlux the anonymous {@code Authentication} object IS
     * present when no token is supplied, so Spring raises {@code AccessDeniedException}
     * (not {@code AuthenticationException}) for unauthenticated requests on protected
     * paths.  Without this handler those requests would receive the default {@code 403}
     * instead of the expected {@code 401}.</p>
     */
    private ServerAccessDeniedHandler jsonAccessDeniedHandler() {
        return (exchange, denied) -> json401Response(exchange.getResponse());
    }

    /** Shared helper — writes {@code {"error":"..."}} with status 401. */
    private Mono<Void> json401Response(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = "{\"error\":\"Unauthorized \u2013 valid Bearer token required\"}"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
