package com.order.processing.product.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for the product-service.
 *
 * <h3>Authorization model</h3>
 * <ul>
 *   <li>{@code /actuator/**}   — public (health checks, reachable by gateway)</li>
 *   <li>{@code /api/**}        — requires authentication (JWT from gateway or direct)</li>
 * </ul>
 *
 * <h3>Design intent</h3>
 * The product-service is a downstream service: in normal operation all traffic
 * arrives via the gateway, which has already validated the token and set the
 * {@code X-Auth-User} / {@code X-Auth-Roles} headers.  The {@link JwtAuthFilter}
 * honours those headers so the JJWT library is not invoked for every gateway
 * request — it is only invoked when a caller hits this service directly.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // ── Stateless REST API — no CSRF, no sessions ──────────────
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── Disable Spring Security's own login/basic challenges ────
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)

                // ── Register our JWT filter before the standard auth filter ─
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // ── Authorization rules ─────────────────────────────────────
                .authorizeHttpRequests(auth -> auth
                        // /management/** — new actuator base-path (health, info, metrics, prometheus)
                        .requestMatchers("/management/**").permitAll()
                        // /actuator/** — kept for backward-compat
                        .requestMatchers("/actuator/**").permitAll()
                        // All API endpoints require a valid identity
                        .anyRequest().authenticated()
                )

                .build();
    }
}
