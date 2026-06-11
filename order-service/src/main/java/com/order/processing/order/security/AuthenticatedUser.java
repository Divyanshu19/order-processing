package com.order.processing.order.security;

/**
 * Immutable value object stored as the <em>principal</em> inside a
 * {@link org.springframework.security.authentication.UsernamePasswordAuthenticationToken}
 * once the JWT (or gateway-forwarded headers) have been verified.
 *
 * <h3>Why a typed principal instead of a plain String?</h3>
 * <ul>
 *   <li>The controller can receive the full identity via
 *       {@code @AuthenticationPrincipal AuthenticatedUser user} without
 *       needing to cast or look up the Security context manually.</li>
 *   <li>Both fields are sourced from the verified JWT — the client can
 *       never supply or override them through the request body.</li>
 * </ul>
 *
 * @param userId   the numeric database primary key from the JWT {@code uid} claim
 * @param username the human-readable login name from the JWT {@code sub} claim
 */
public record AuthenticatedUser(Long userId, String username) {

    /**
     * Compact factory used by {@link JwtAuthFilter} when building the token
     * from gateway-forwarded headers ({@code X-User-Id} / {@code X-Auth-User}).
     *
     * @param userIdHeader value of the {@code X-User-Id} header (numeric string)
     * @param username     value of the {@code X-Auth-User} header
     * @return parsed {@link AuthenticatedUser}
     * @throws IllegalArgumentException if {@code userIdHeader} is not a valid long
     */
    public static AuthenticatedUser fromHeaders(String userIdHeader, String username) {
        return new AuthenticatedUser(Long.parseLong(userIdHeader), username);
    }
}
