package com.order.processing.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Auth Service.
 *
 * <p>Exposes a single public endpoint:
 * <pre>
 *   POST /auth/login   →  { "token": "&lt;signed-JWT&gt;" }
 * </pre>
 *
 * <p>All other paths require a valid {@code Authorization: Bearer &lt;token&gt;} header.
 */
@SpringBootApplication
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
