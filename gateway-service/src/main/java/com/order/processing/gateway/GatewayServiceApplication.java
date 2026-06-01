package com.order.processing.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry-point for the API Gateway.
 *
 * <p>Spring Cloud Gateway runs on the Reactive (Netty) stack. All routing rules
 * are declared declaratively in {@code application.yml}. An optional
 * {@link GatewayConfig} bean demonstrates programmatic route registration.
 */
@SpringBootApplication
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
