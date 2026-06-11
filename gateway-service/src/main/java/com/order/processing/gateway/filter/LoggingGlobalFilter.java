package com.order.processing.gateway.filter;

import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Global filter that logs every inbound request and its downstream response,
 * including the Micrometer {@code traceId} so gateway logs can be correlated
 * with downstream service logs in Zipkin and any log aggregator.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so it wraps all other filters
 * and records accurate end-to-end latency.
 *
 * <p>Sample log output:
 * <pre>
 *   [GATEWAY] → GET /product/1 | client=127.0.0.1 | traceId=4bf92f3577b34da6
 *   [GATEWAY] ← GET /product/1 | status=200 | latency=23ms | traceId=4bf92f3577b34da6
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingGlobalFilter implements GlobalFilter, Ordered {

    /**
     * Micrometer {@link Tracer} — provided by {@code micrometer-tracing-bridge-brave}
     * autoconfiguration.  Used to read the current span's traceId for log correlation.
     * May be {@code null} before a trace context is established (first reactive exchange).
     */
    private final Tracer tracer;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long startTime = Instant.now().toEpochMilli();

        // Read the traceId from the current Micrometer span (populated by
        // micrometer-tracing-bridge-brave after the gateway's reactive filter chain
        // establishes the trace context from incoming B3 headers or creates a new root span).
        // Fall back to the raw X-B3-TraceId header, then "n/a".
        String traceId = resolveTraceId(request);

        log.info("[GATEWAY] → {} {} | client={} | traceId={}",
                request.getMethod(),
                request.getURI().getPath(),
                request.getRemoteAddress() != null
                        ? request.getRemoteAddress().getAddress().getHostAddress()
                        : "unknown",
                traceId);

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();
            long latency = Instant.now().toEpochMilli() - startTime;
            // Re-read after chain: the span is still active in the then() callback.
            String responseTraceId = resolveTraceId(request);

            log.info("[GATEWAY] ← {} {} | status={} | latency={}ms | traceId={}",
                    request.getMethod(),
                    request.getURI().getPath(),
                    response.getStatusCode() != null ? response.getStatusCode().value() : "unknown",
                    latency,
                    responseTraceId);
        }));
    }

    /**
     * Returns the traceId for the current request using the following priority:
     * <ol>
     *   <li>The Micrometer {@link io.micrometer.tracing.Span} traceId (most accurate —
     *       the span is created by the gateway's reactive instrumentation).</li>
     *   <li>The raw {@code X-B3-TraceId} or {@code b3} header forwarded by an upstream
     *       caller (e.g. a test harness or another gateway layer).</li>
     *   <li>{@code "n/a"} if no trace context is active at all.</li>
     * </ol>
     */
    private String resolveTraceId(ServerHttpRequest request) {
        // 1. Micrometer Tracer (preferred — always reflects the active span)
        if (tracer != null && tracer.currentSpan() != null) {
            io.micrometer.tracing.TraceContext ctx = tracer.currentSpan().context();
            if (ctx != null) {
                return ctx.traceId();
            }
        }
        // 2. Raw B3 single-header (compact format)
        String b3 = request.getHeaders().getFirst("b3");
        if (b3 != null && !b3.isBlank()) {
            return b3.split("-")[0]; // traceId is the first segment
        }
        // 3. Raw B3 multi-header
        String x = request.getHeaders().getFirst("X-B3-TraceId");
        if (x != null && !x.isBlank()) {
            return x;
        }
        return "n/a";
    }
}
