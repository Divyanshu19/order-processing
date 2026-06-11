package com.order.processing.order.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Factory that builds a {@link SecurityContext} containing an
 * {@link AuthenticatedUser} principal for use in integration tests.
 *
 * <p>This mirrors exactly what {@link JwtAuthFilter} does at runtime, so the
 * test exercises the full controller code path including the
 * {@code @AuthenticationPrincipal} injection.
 */
public class WithMockOrderUserSecurityContextFactory
        implements WithSecurityContextFactory<WithMockOrderUser> {

    @Override
    public SecurityContext createSecurityContext(WithMockOrderUser annotation) {
        AuthenticatedUser principal =
                new AuthenticatedUser(annotation.userId(), annotation.username());

        List<SimpleGrantedAuthority> authorities = Arrays.stream(annotation.roles())
                .map(SimpleGrantedAuthority::new)
                .toList();

        var authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        return context;
    }
}
