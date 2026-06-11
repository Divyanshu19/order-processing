package com.order.processing.product.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/**
 * Unit tests for {@link JwtAuthFilter}.
 *
 * <p>Uses raw Spring mocks — no Spring Boot context, no database, no Kafka.
 * Tests are fast (< 50 ms each) and fully isolated.
 *
 * <h3>Coverage</h3>
 * <ul>
 *   <li>Gateway-header path: {@code X-Auth-User} + {@code X-Auth-Roles}</li>
 *   <li>Direct Bearer-token path (valid token)</li>
 *   <li>Invalid token → 401 short-circuit, filter chain NOT invoked</li>
 *   <li>Missing auth → filter chain invoked, no principal set</li>
 * </ul>
 */
class JwtAuthFilterTest {

    private static final String SECRET = "test-secret-key-only-for-unit-tests-min32chars";
    private static final SecretKey SIGNING_KEY =
            Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter();
        // Inject the secret via ReflectionTestUtils (replaces @Value injection)
        ReflectionTestUtils.setField(filter, "jwtSecret", SECRET);
        filter.init();                          // trigger @PostConstruct manually
        SecurityContextHolder.clearContext();   // isolate tests
    }

    // ── Gateway-header path ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Gateway-header path (X-Auth-User forwarded by gateway)")
    class GatewayHeaderPath {

        @Test
        @DisplayName("sets SecurityContext principal from X-Auth-User header")
        void gatewayHeader_validUser_setsAuthentication() throws Exception {
            MockHttpServletRequest  request  = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            request.addHeader("X-Auth-User",  "alice");
            request.addHeader("X-Auth-Roles", "ROLE_USER,ROLE_ADMIN");

            filter.doFilterInternal(request, response, chain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isEqualTo("alice");
            assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");

            // Filter chain must be invoked (request is not short-circuited)
            verify(chain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("sets empty authorities when X-Auth-Roles is missing")
        void gatewayHeader_missingRoles_setsEmptyAuthorities() throws Exception {
            MockHttpServletRequest  request  = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            request.addHeader("X-Auth-User", "bob");
            // No X-Auth-Roles header

            filter.doFilterInternal(request, response, chain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.getPrincipal()).isEqualTo("bob");
            assertThat(auth.getAuthorities()).isEmpty();
            verify(chain).doFilter(request, response);
        }
    }

    // ── Direct Bearer-token path ──────────────────────────────────────────────

    @Nested
    @DisplayName("Direct Bearer-token path")
    class BearerTokenPath {

        @Test
        @DisplayName("valid token sets SecurityContext principal and authorities")
        void bearer_validToken_setsAuthentication() throws Exception {
            String token = buildToken("charlie", List.of("ROLE_USER"), 60_000);

            MockHttpServletRequest  request  = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            request.addHeader(AUTHORIZATION, "Bearer " + token);

            filter.doFilterInternal(request, response, chain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isEqualTo("charlie");
            assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_USER");

            verify(chain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("expired token → 401, filter chain NOT called")
        void bearer_expiredToken_returns401() throws Exception {
            // Token expired 60 seconds ago
            String token = buildToken("dave", List.of("ROLE_USER"), -60_000);

            MockHttpServletRequest  request  = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            request.addHeader(AUTHORIZATION, "Bearer " + token);

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("error");
            // Filter chain must NOT be invoked on invalid tokens
            verify(chain, never()).doFilter(any(), any());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("tampered token → 401, filter chain NOT called")
        void bearer_tamperedToken_returns401() throws Exception {
            String token = buildToken("eve", List.of("ROLE_USER"), 60_000);
            String tampered = token.substring(0, token.length() - 4) + "XXXX";

            MockHttpServletRequest  request  = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            request.addHeader(AUTHORIZATION, "Bearer " + tampered);

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    // ── Missing credentials ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Missing credentials")
    class MissingCredentials {

        @Test
        @DisplayName("no Authorization header → chain invoked without principal")
        void noHeader_chainInvokedWithoutPrincipal() throws Exception {
            MockHttpServletRequest  request  = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("Authorization header without 'Bearer ' prefix → chain invoked without principal")
        void basicAuthHeader_chainInvokedWithoutPrincipal() throws Exception {
            MockHttpServletRequest  request  = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            request.addHeader(AUTHORIZATION, "Basic dXNlcjpwYXNz");

            filter.doFilterInternal(request, response, chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(chain).doFilter(request, response);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a compact signed JWT with the test signing key.
     *
     * @param subject   the {@code sub} claim
     * @param roles     list of role strings stored in the {@code roles} claim
     * @param ttlMillis token lifetime in milliseconds; negative = already expired
     */
    private String buildToken(String subject, List<String> roles, long ttlMillis) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + ttlMillis);
        return Jwts.builder()
                .subject(subject)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(SIGNING_KEY)
                .compact();
    }
}
