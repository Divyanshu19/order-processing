package com.order.processing.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code POST /auth/login}.
 *
 * <pre>
 * {
 *   "username": "admin",
 *   "password": "secret"
 * }
 * </pre>
 */
@Data
public class LoginRequest {

    @NotBlank(message = "Username must not be blank")
    private String username;

    @NotBlank(message = "Password must not be blank")
    private String password;
}
