package com.order.processing.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global filter that propagates (or generates) a {@code X-Request-Id} header.
 *
 * <p>If the client already supplies the header its value is kept; otherwise a
 * new UUID is minted.  The ID is forwarded to downstream services and echoed
 * back in the response so callers can correlate logs end-to-end.
 */
@Slf4j
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Runs just after {@link LoggingGlobalFilter} so the ID appears in logs. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders()
                .getFirst(REQUEST_ID_HEADER);

        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            log.debug("[GATEWAY] Generated {}: {}", REQUEST_ID_HEADER, requestId);
        }

        final String finalRequestId = requestId;

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(REQUEST_ID_HEADER, finalRequestId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        // Register the response header BEFORE the response is committed.
        // beforeCommit() fires just before headers are flushed to the wire,
        // while they are still mutable.  Using .then(Mono.fromRunnable(...))
        // would run AFTER the body write, at which point getHeaders() returns
        // a ReadOnlyHttpHeaders snapshot → UnsupportedOperationException.
        mutatedExchange.getResponse().beforeCommit(() -> {
            mutatedExchange.getResponse().getHeaders()
                    .add(REQUEST_ID_HEADER, finalRequestId);
            return Mono.empty();
        });

        return chain.filter(mutatedExchange);
    }
}
