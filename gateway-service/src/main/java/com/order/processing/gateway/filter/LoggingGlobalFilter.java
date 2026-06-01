package com.order.processing.gateway.filter;

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
 * Global filter that logs every inbound request and its downstream response.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so it wraps all other filters
 * and records accurate end-to-end latency.
 *
 * <p>Sample log output:
 * <pre>
 *   [GATEWAY] → GET /product/1 | client=127.0.0.1
 *   [GATEWAY] ← GET /product/1 | status=200 | latency=23ms
 * </pre>
 */
@Slf4j
@Component
public class LoggingGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long startTime = Instant.now().toEpochMilli();

        log.info("[GATEWAY] → {} {} | client={}",
                request.getMethod(),
                request.getURI().getPath(),
                request.getRemoteAddress() != null
                        ? request.getRemoteAddress().getAddress().getHostAddress()
                        : "unknown");

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();
            long latency = Instant.now().toEpochMilli() - startTime;

            log.info("[GATEWAY] ← {} {} | status={} | latency={}ms",
                    request.getMethod(),
                    request.getURI().getPath(),
                    response.getStatusCode() != null ? response.getStatusCode().value() : "unknown",
                    latency);
        }));
    }
}
