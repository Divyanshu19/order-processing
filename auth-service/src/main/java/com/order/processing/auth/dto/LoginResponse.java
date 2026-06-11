package com.order.processing.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response body returned by {@code POST /auth/login} on success.
 *
 * <pre>
 * {
 *   "token":     "&lt;signed-JWT&gt;",
 *   "type":      "Bearer",
 *   "expiresIn": 3600
 * }
 * </pre>
 */
@Data
@AllArgsConstructor
public class LoginResponse {

    /** The signed JWT. */
    private String token;

    /** Always {@code "Bearer"} — matches the Authorization header scheme. */
    private String type;

    /** Token lifetime in seconds. */
    private long expiresIn;
}
