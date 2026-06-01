package com.order.processing.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Programmatic route definitions – an alternative / complement to the YAML
 * configuration found in {@code application.yml}.
 *
 * <p>These beans are activated only when the {@code java-routes} Spring profile
 * is active.  In all other environments the YAML routes (which are always
 * loaded) take precedence and these beans are simply absent, avoiding
 * duplicate-route warnings.
 *
 * <p>Route resolution order (lower number = higher priority):
 * <ol>
 *   <li>{@code product-route}  – /product/** → product-service (port 8081)</li>
 *   <li>{@code order-route}    – /order/**   → order-service   (port 8080)</li>
 *   <li>{@code payment-route}  – /payment/** → payment-service (port 8082)</li>
 * </ol>
 *
 * <p>Each route strips the leading path segment (e.g. {@code /product}) and
 * prepends the downstream context-path ({@code /api}) so that:
 * <pre>
 *   GET  :8080/product/1          →  GET  :8081/api/products/1
 *   POST :8080/order              →  POST :8080/api/orders
 *   POST :8080/payment/process    →  POST :8082/api/payments/process
 * </pre>
 */
@Configuration
@Profile("java-routes")
public class GatewayConfig {

    /**
     * Builds the three core routes using the fluent DSL.
     *
     * <p>The {@code RewritePath} filter transforms the inbound URI:
     * <ul>
     *   <li>Captures everything after the route prefix in group {@code segment}.</li>
     *   <li>Rewrites to {@code /api/<plural-resource>/${segment}}.</li>
     * </ul>
     */
    @Bean
    public RouteLocator coreRoutes(RouteLocatorBuilder builder) {
        return builder.routes()

            // ── /product/** → product-service ─────────────────────────────
            .route("product-route", r -> r
                .path("/product/**")
                .filters(f -> f
                    .rewritePath("/product/(?<segment>.*)", "/api/products/${segment}")
                    .addRequestHeader("X-Gateway-Source", "gateway-service")
                    .addResponseHeader("X-Served-By", "product-service")
                )
                .uri("http://localhost:8081")
            )

            // ── /order/** → order-service ──────────────────────────────────
            .route("order-route", r -> r
                .path("/order/**")
                .filters(f -> f
                    .rewritePath("/order/(?<segment>.*)", "/api/orders/${segment}")
                    .addRequestHeader("X-Gateway-Source", "gateway-service")
                    .addResponseHeader("X-Served-By", "order-service")
                )
                .uri("http://localhost:8080")
            )

            // ── /payment/** → payment-service ─────────────────────────────
            .route("payment-route", r -> r
                .path("/payment/**")
                .filters(f -> f
                    .rewritePath("/payment/(?<segment>.*)", "/api/payments/${segment}")
                    .addRequestHeader("X-Gateway-Source", "gateway-service")
                    .addResponseHeader("X-Served-By", "payment-service")
                )
                .uri("http://localhost:8082")
            )

            .build();
    }
}
