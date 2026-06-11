package com.order.processing.order.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /**
     * WebClient bean pre-configured with the product-service base URL and
     * Micrometer Observation instrumentation.
     *
     * <p>Injected into {@link com.order.processing.order.client.ProductServiceClient}
     * for the circuit-breaker-protected {@code GET /products/{id}} call used by
     * the {@code GET /orders/{id}} enriched endpoint.
     *
     * <p><b>Tracing:</b> passing the {@link ObservationRegistry} to the builder
     * activates the {@code WebClientObservationFilter} which:
     * <ul>
     *   <li>Creates a child span for every outbound HTTP request.</li>
     *   <li>Injects B3 trace headers ({@code X-B3-TraceId}, {@code X-B3-SpanId},
     *       {@code X-B3-Sampled}) into the request so product-service can continue
     *       the same distributed trace.</li>
     *   <li>Records the span (with status, HTTP method, URI) in Zipkin via the
     *       {@code zipkin-reporter-brave} reporter already on the classpath.</li>
     * </ul>
     *
     * <p><b>Circuit Breaker compatibility:</b> Using {@code WebClient} (instead of
     * {@code RestTemplate}) because Resilience4j's {@code @CircuitBreaker} wraps
     * the method synchronously — the reactive chain is blocked via {@code .block()}
     * so the CB state machine tracks successes/failures correctly while still
     * benefiting from WebClient's richer error-handling API.
     */
    @Bean
    public WebClient productServiceWebClient(
            @Value("${product-service.base-url}") String baseUrl,
            ObservationRegistry observationRegistry) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .observationRegistry(observationRegistry)
                .build();
    }
}
