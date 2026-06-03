package com.order.processing.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /**
     * WebClient bean pre-configured with the product-service base URL.
     * Injected into {@link com.order.processing.order.client.ProductServiceClient}
     * for the circuit-breaker-protected {@code GET /products/{id}} call used by
     * the {@code GET /orders/{id}} enriched endpoint.
     *
     * <p>Using {@code WebClient} (instead of {@code RestTemplate}) here because
     * Resilience4j's {@code @CircuitBreaker} annotation wraps the method
     * synchronously — the reactive chain is blocked via {@code .block()} so the
     * circuit breaker state machine can track successes and failures correctly
     * while still benefiting from WebClient's richer error-handling API.
     */
    @Bean
    public WebClient productServiceWebClient(
            @Value("${product-service.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
