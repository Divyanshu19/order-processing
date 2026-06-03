package com.order.processing.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Wires structured log listeners onto every Resilience4j CircuitBreaker and
 * Retry instance registered in the application context.
 *
 * <h3>Why subscribe in {@code @EventListener(ApplicationReadyEvent)} ?</h3>
 * Resilience4j instances are created lazily on the first decorated call.
 * {@code ApplicationReadyEvent} fires after the full context is initialised
 * <em>and</em> after the instances are registered, so every instance is
 * guaranteed to be present.  Subscribing in a {@code @Bean} method or
 * {@code @PostConstruct} may race with instance creation.
 *
 * <h3>Log format</h3>
 * All lines are prefixed with {@code [CB]} or {@code [Retry]} so they can be
 * isolated in any log aggregator with a single filter:
 * <pre>
 *   grep -E '\[CB\]|\[Retry\]' /tmp/order-service.log
 * </pre>
 *
 * <h3>Circuit Breaker events logged</h3>
 * <ul>
 *   <li>{@code STATE_TRANSITION} — every CLOSED→OPEN, OPEN→HALF_OPEN,
 *       HALF_OPEN→CLOSED transition (and FORCED_OPEN / DISABLED variants)</li>
 *   <li>{@code FAILURE_RATE_EXCEEDED} — failure rate crossed the threshold</li>
 *   <li>{@code SLOW_CALL_RATE_EXCEEDED} — slow-call rate crossed threshold</li>
 *   <li>{@code CALL_NOT_PERMITTED} — a call was rejected because CB is OPEN</li>
 *   <li>{@code ERROR} — a call failed and was counted against the window</li>
 *   <li>{@code SUCCESS} — a call succeeded and was counted</li>
 *   <li>{@code IGNORED_ERROR} — an exception matched ignore-exceptions list</li>
 * </ul>
 *
 * <h3>Retry events logged</h3>
 * <ul>
 *   <li>{@code RETRY} — an attempt failed and will be retried</li>
 *   <li>{@code SUCCESS} — an attempt eventually succeeded</li>
 *   <li>{@code ERROR} — all attempts exhausted, propagating failure</li>
 *   <li>{@code IGNORED_ERROR} — exception matched ignore-exceptions list</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ResilienceEventLoggerConfig {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry          retryRegistry;

    public ResilienceEventLoggerConfig(CircuitBreakerRegistry circuitBreakerRegistry,
                                       RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry          = retryRegistry;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bootstrap: attach listeners once the application is fully started
    // ─────────────────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void attachResilienceListeners() {

        // ── Circuit Breaker listeners ─────────────────────────────────────────
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::attachCircuitBreakerListeners);

        // Also attach to any CB instance created dynamically after startup
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> attachCircuitBreakerListeners(event.getAddedEntry()));

        // ── Retry listeners ───────────────────────────────────────────────────
        retryRegistry.getAllRetries().forEach(this::attachRetryListeners);

        // Also attach to any Retry instance created dynamically after startup
        retryRegistry.getEventPublisher()
                .onEntryAdded(event -> attachRetryListeners(event.getAddedEntry()));

        log.info("[Resilience4j] Event listeners attached — circuit breakers: {}, retries: {}",
                circuitBreakerRegistry.getAllCircuitBreakers().stream()
                        .map(CircuitBreaker::getName).toList(),
                retryRegistry.getAllRetries().stream()
                        .map(r -> r.getName()).toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Circuit Breaker event subscriptions
    // ─────────────────────────────────────────────────────────────────────────

    private void attachCircuitBreakerListeners(CircuitBreaker cb) {
        String name = cb.getName();
        var pub = cb.getEventPublisher();

        // ── STATE TRANSITION — most important log line ────────────────────────
        // Emitted on: CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED,
        //             CLOSED→FORCED_OPEN, CLOSED→DISABLED, etc.
        pub.onStateTransition(event -> {
            CircuitBreaker.StateTransition tx = event.getStateTransition();
            String from = tx.getFromState().name();
            String to   = tx.getToState().name();

            // Choose log level based on severity of the new state
            switch (tx.getToState()) {
                case OPEN ->
                    log.error("[CB] '{}' STATE TRANSITION  {} ──▶ {}  | circuit is now OPEN — " +
                              "all calls will be short-circuited to fallback",
                              name, from, to);
                case HALF_OPEN ->
                    log.warn("[CB] '{}' STATE TRANSITION  {} ──▶ {}  | probe calls are now allowed",
                             name, from, to);
                case CLOSED ->
                    log.info("[CB] '{}' STATE TRANSITION  {} ──▶ {}  | circuit is HEALTHY again",
                             name, from, to);
                default ->
                    log.warn("[CB] '{}' STATE TRANSITION  {} ──▶ {}",
                             name, from, to);
            }
        });

        // ── FAILURE RATE EXCEEDED — threshold crossed, circuit about to open ──
        pub.onFailureRateExceeded(event ->
            log.error("[CB] '{}' FAILURE_RATE_EXCEEDED  rate={}%  threshold={}%  buffered={}  failed={}",
                      name,
                      String.format("%.1f", event.getFailureRate()),
                      String.format("%.1f", cb.getCircuitBreakerConfig().getFailureRateThreshold()),
                      cb.getMetrics().getNumberOfBufferedCalls(),
                      cb.getMetrics().getNumberOfFailedCalls())
        );

        // ── SLOW CALL RATE EXCEEDED ───────────────────────────────────────────
        pub.onSlowCallRateExceeded(event ->
            log.warn("[CB] '{}' SLOW_CALL_RATE_EXCEEDED  slowRate={}%  threshold={}%",
                     name,
                     String.format("%.1f", event.getSlowCallRate()),
                     String.format("%.1f", cb.getCircuitBreakerConfig().getSlowCallRateThreshold()))
        );

        // ── CALL NOT PERMITTED (circuit is OPEN, call rejected) ───────────────
        pub.onCallNotPermitted(event ->
            log.warn("[CB] '{}' CALL_NOT_PERMITTED  state={}  notPermitted={}  " +
                     "— instant fallback, no downstream call made",
                     name,
                     cb.getState(),
                     cb.getMetrics().getNumberOfNotPermittedCalls())
        );

        // ── ERROR — call failed, counted in the sliding window ────────────────
        pub.onError(event ->
            log.warn("[CB] '{}' ERROR  duration={}ms  state={}  failedCalls={}  " +
                     "buffered={}  cause={}",
                     name,
                     event.getElapsedDuration().toMillis(),
                     cb.getState(),
                     cb.getMetrics().getNumberOfFailedCalls(),
                     cb.getMetrics().getNumberOfBufferedCalls(),
                     event.getThrowable().getMessage())
        );

        // ── SUCCESS — call succeeded, counted in the sliding window ───────────
        pub.onSuccess(event ->
            log.debug("[CB] '{}' SUCCESS  duration={}ms  state={}  buffered={}  failedCalls={}",
                      name,
                      event.getElapsedDuration().toMillis(),
                      cb.getState(),
                      cb.getMetrics().getNumberOfBufferedCalls(),
                      cb.getMetrics().getNumberOfFailedCalls())
        );

        // ── IGNORED ERROR — exception matched ignore-exceptions list ──────────
        pub.onIgnoredError(event ->
            log.debug("[CB] '{}' IGNORED_ERROR  duration={}ms  cause={}  " +
                      "— not counted against failure rate",
                      name,
                      event.getElapsedDuration().toMillis(),
                      event.getThrowable().getMessage())
        );

        log.debug("[Resilience4j] Circuit breaker listeners attached for '{}'", name);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retry event subscriptions
    // ─────────────────────────────────────────────────────────────────────────

    private void attachRetryListeners(io.github.resilience4j.retry.Retry retry) {
        String name = retry.getName();
        var pub = retry.getEventPublisher();

        // ── RETRY — an attempt failed; another will be made ───────────────────
        pub.onRetry(event ->
            log.warn("[Retry] '{}' ATTEMPT  attempt={}/{}  waitMs={}  cause={}",
                     name,
                     event.getNumberOfRetryAttempts(),
                     retry.getRetryConfig().getMaxAttempts(),
                     event.getWaitInterval().toMillis(),
                     event.getLastThrowable() != null
                             ? event.getLastThrowable().getMessage()
                             : "n/a")
        );

        // ── SUCCESS — a call succeeded (possibly after 1+ retries) ───────────
        pub.onSuccess(event ->
            log.info("[Retry] '{}' SUCCESS  attempt={}/{}  — call succeeded{}",
                     name,
                     event.getNumberOfRetryAttempts(),
                     retry.getRetryConfig().getMaxAttempts(),
                     event.getNumberOfRetryAttempts() > 0
                             ? " after " + event.getNumberOfRetryAttempts() + " retries"
                             : " on first try")
        );

        // ── ERROR — all attempts exhausted, exception propagates ──────────────
        pub.onError(event ->
            log.error("[Retry] '{}' ALL_ATTEMPTS_EXHAUSTED  attempts={}  cause={}",
                      name,
                      event.getNumberOfRetryAttempts(),
                      event.getLastThrowable() != null
                              ? event.getLastThrowable().getMessage()
                              : "n/a")
        );

        // ── IGNORED ERROR — exception matched ignore-exceptions list ──────────
        pub.onIgnoredError(event ->
            log.debug("[Retry] '{}' IGNORED_ERROR  cause={}  — not retried",
                      name,
                      event.getLastThrowable() != null
                              ? event.getLastThrowable().getMessage()
                              : "n/a")
        );

        log.debug("[Resilience4j] Retry listeners attached for '{}'", name);
    }
}
