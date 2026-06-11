package com.order.processing.order.security;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test annotation that injects an {@link AuthenticatedUser} principal into the
 * Spring Security context, matching the exact principal type produced by
 * {@link JwtAuthFilter} at runtime.
 *
 * <h3>Why not {@code @WithMockUser}?</h3>
 * {@code @WithMockUser} stores a plain {@code String} as the principal.  The
 * {@code OrderController} uses {@code @AuthenticationPrincipal AuthenticatedUser},
 * which requires the principal to be an {@link AuthenticatedUser} record.
 * Using {@code @WithMockUser} would cause a {@code ClassCastException} at runtime.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @WithMockOrderUser                          // default: userId=42, username="test-user"
 * @WithMockOrderUser(userId=99, username="admin")
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@WithSecurityContext(factory = WithMockOrderUserSecurityContextFactory.class)
public @interface WithMockOrderUser {

    /** The numeric user ID embedded in the {@code uid} JWT claim. */
    long userId() default 42L;

    /** The username embedded in the {@code sub} JWT claim. */
    String username() default "test-user";

    /** The role strings granted to this principal. */
    String[] roles() default {"ROLE_USER"};
}
