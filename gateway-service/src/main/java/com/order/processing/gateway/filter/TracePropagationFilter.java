package com.order.processing.gateway.filter;

import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global gateway filter that propagates B3 distributed tracing headers from
 * the Micrometer {@link Tracer} into every forwarded HTTP request.
 *
 * <h3>Why this filter exists</h3>
 * <p>Spring Cloud Gateway (Reactive / Netty) integrates with Micrometer Tracing
 * to create a root span per request.  The {@code micrometer-tracing-bridge-brave}
 * + Spring WebFlux instrumentation automatically injects B3 headers into
 * downstream requests made via the reactive {@code WebClient}.  However, for
 * Spring Cloud Gateway's proxy leg (the routed request to a downstream service)
 * we explicitly ensure the trace headers are forwarded so the downstream
 * service (order-service, product-service, payment-service) receives them and
 * can continue the same trace.
 *
 * <h3>Headers forwarded</h3>
 * <pre>
 *   X-B3-TraceId   — 128-bit trace identifier (32 hex chars)
 *   X-B3-SpanId    — 64-bit span identifier (16 hex chars)
 *   X-B3-Sampled   — "1" (always sampled, controlled by tracing.sampling.probability)
 * </pre>
 *
 * <h3>Runs at</h3>
 * {@code Ordered.HIGHEST_PRECEDENCE + 5} — just after {@link RequestIdGlobalFilter}
 * but before the routing filter sees the request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TracePropagationFilter implements GlobalFilter, Ordered {

    private final Tracer tracer;

    /** Must run before the NettyRoutingFilter (order = Integer.MAX_VALUE). */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Read the current active span set by micrometer-tracing-bridge-brave
        io.micrometer.tracing.Span currentSpan = tracer.currentSpan();

        if (currentSpan == null) {
            // No active trace context — pass through unchanged.
            // This can happen for actuator/health calls that are excluded from tracing.
            return chain.filter(exchange);
        }

        io.micrometer.tracing.TraceContext ctx = currentSpan.context();
        if (ctx == null) {
            return chain.filter(exchange);
        }

        String traceId = ctx.traceId();
        String spanId  = ctx.spanId();

        log.debug("[TraceProp] Forwarding B3 headers: X-B3-TraceId={} X-B3-SpanId={}", traceId, spanId);

        // Mutate the forwarded request to carry explicit B3 headers.
        // Spring Cloud Gateway's reactive pipeline already injects them via
        // Brave's HTTP instrumentation, but we make it explicit so it is
        // visible in access logs and dev tooling (e.g. Postman echo headers).
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-B3-TraceId", traceId)
                .header("X-B3-SpanId",  spanId)
                .header("X-B3-Sampled", "1")
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }
}
