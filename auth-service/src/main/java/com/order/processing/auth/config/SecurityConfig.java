package com.order.processing.auth.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the auth-service.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>{@code POST /auth/login}   — public (no token needed to obtain a token)</li>
 *   <li>{@code GET  /actuator/**}  — public  (health-check, reachable by the gateway)</li>
 *   <li>Everything else            — requires an authenticated session (won't be
 *       reached in normal flow because the auth-service only exposes /auth/*)</li>
 * </ul>
 *
 * <p>CSRF is disabled because this is a stateless JSON API.
 * Sessions are {@link SessionCreationPolicy#STATELESS}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless REST API — no CSRF token needed
                .csrf(AbstractHttpConfigurer::disable)

                // No server-side sessions — tokens carry all state
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Error re-dispatches (e.g. @Valid 400 errors forwarded to /error)
                        // must be permitted, otherwise Spring Security turns them into 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Login endpoint must be public
                        .requestMatchers("/auth/login").permitAll()
                        // /management/** — new actuator base-path (health, info, metrics, prometheus)
                        .requestMatchers("/management/**").permitAll()
                        // /actuator/** — kept for backward-compat
                        .requestMatchers("/actuator/**").permitAll()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )

                // Disable default form-login and HTTP-Basic — we use JWT only
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .build();
    }
}
