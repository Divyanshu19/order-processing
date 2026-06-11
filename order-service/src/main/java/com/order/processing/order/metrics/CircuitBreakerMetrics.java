package com.order.processing.order.metrics;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Exports Resilience4j circuit breaker state as a Micrometer {@link Gauge}
 * so Prometheus can scrape and alert on it.
 *
 * <h3>Why a custom Gauge instead of relying on Resilience4j's built-in micrometer support?</h3>
 * <p>Resilience4j ships a {@code resilience4j-micrometer} module that publishes
 * several CB metrics automatically.  We register our own explicit gauge here
 * because:
 * <ol>
 *   <li>It gives us a single numeric metric ({@code circuit_breaker_state})
 *       that is trivially easy to graph and alert on: 0 = CLOSED, 1 = OPEN,
 *       2 = HALF_OPEN.</li>
 *   <li>It demonstrates the Gauge registration pattern clearly.</li>
 *   <li>It lets us define a canonical metric name consistent across all services
 *       that have circuit breakers.</li>
 * </ol>
 *
 * <h3>Prometheus query examples</h3>
 * <pre>
 *   # Is any circuit breaker currently open?
 *   circuit_breaker_state == 1
 *
 *   # Alert: CB has been OPEN for more than 30 seconds
 *   (circuit_breaker_state == 1) for 30s
 * </pre>
 *
 * <h3>State encoding</h3>
 * <pre>
 *   0 → CLOSED    (healthy, calls flowing)
 *   1 → OPEN      (failing, calls short-circuited to fallback)
 *   2 → HALF_OPEN (probing, limited calls allowed)
 * </pre>
 */
@Slf4j
@Configuration
public class CircuitBreakerMetrics {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry          meterRegistry;

    public CircuitBreakerMetrics(CircuitBreakerRegistry circuitBreakerRegistry,
                                 MeterRegistry meterRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.meterRegistry          = meterRegistry;
    }

    /**
     * Registers one {@code circuit_breaker_state} gauge per circuit breaker
     * instance after the application context is fully started.
     *
     * <p>We listen for {@link ApplicationReadyEvent} (not {@code @PostConstruct})
     * because Resilience4j creates CB instances lazily on the first decorated
     * call.  By {@code ApplicationReadyEvent} all instances that will be used
     * are already present in the registry.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerCircuitBreakerGauges() {
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(this::registerGaugeFor);

        // Also attach to any CB instance created after startup (dynamic decoration)
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> registerGaugeFor(event.getAddedEntry()));

        log.info("[Metrics] Circuit breaker state gauges registered for: {}",
                circuitBreakerRegistry.getAllCircuitBreakers()
                        .stream()
                        .map(CircuitBreaker::getName)
                        .toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void registerGaugeFor(CircuitBreaker cb) {
        // ── State gauge: 0=CLOSED, 1=OPEN, 2=HALF_OPEN ───────────────────────
        Gauge.builder("circuit_breaker_state", cb, this::encodeState)
                .description("Resilience4j circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
                .tag("name", cb.getName())
                .register(meterRegistry);

        // ── Pre-register transition counters for well-known state pairs ───────
        // This ensures the counters appear in /actuator/prometheus with a zero
        // value from startup, even before any transition has occurred.
        for (String from : new String[]{"CLOSED", "OPEN", "HALF_OPEN"}) {
            for (String to : new String[]{"CLOSED", "OPEN", "HALF_OPEN"}) {
                if (!from.equals(to)) {
                    buildTransitionCounter(cb.getName(), from, to);
                }
            }
        }

        // ── Subscribe: increment the correct counter on each state transition ──
        cb.getEventPublisher().onStateTransition(event -> {
            String from = event.getStateTransition().getFromState().name();
            String to   = event.getStateTransition().getToState().name();
            // Normalise FORCED_OPEN → OPEN and DISABLED / METRICS_ONLY → CLOSED
            // so counters always use the canonical three-state vocabulary.
            String normalisedFrom = normaliseState(from);
            String normalisedTo   = normaliseState(to);
            buildTransitionCounter(cb.getName(), normalisedFrom, normalisedTo).increment();
            log.debug("[Metrics] circuit_breaker_transitions_total{{name={}, from={}, to={}}} incremented",
                      cb.getName(), normalisedFrom, normalisedTo);
        });

        log.debug("[Metrics] Gauge and transition counters registered for circuit breaker '{}'", cb.getName());
    }

    /**
     * Builds (or returns the already-registered) transition counter for the
     * given CB name and {@code from → to} state pair.
     *
     * <p>Micrometer's {@code Counter.builder(...).register(registry)} is
     * idempotent — calling it multiple times with the same name+tags returns
     * the same {@link Counter} instance from the registry cache.
     */
    private Counter buildTransitionCounter(String name, String from, String to) {
        return Counter.builder("circuit_breaker_transitions_total")
                .description("Total number of Resilience4j circuit breaker state transitions by direction")
                .tag("name", name)
                .tag("from", from)
                .tag("to",   to)
                .register(meterRegistry);
    }

    /**
     * Maps extended Resilience4j state names to the canonical three-state vocabulary
     * (CLOSED, OPEN, HALF_OPEN) used as counter tags, so dashboards and alerts
     * don't need to handle FORCED_OPEN, DISABLED, or METRICS_ONLY variants.
     */
    private String normaliseState(String state) {
        return switch (state) {
            case "FORCED_OPEN"   -> "OPEN";
            case "DISABLED",
                 "METRICS_ONLY" -> "CLOSED";
            default              -> state;   // CLOSED, OPEN, HALF_OPEN — already canonical
        };
    }

    /**
     * Maps a Resilience4j {@link CircuitBreaker.State} to a numeric value
     * that Prometheus can graph and threshold-alert on.
     *
     * @param cb the circuit breaker whose current state to read
     * @return 0 (CLOSED), 1 (OPEN), 2 (HALF_OPEN), or -1 (unknown / disabled)
     */
    private double encodeState(CircuitBreaker cb) {
        return switch (cb.getState()) {
            case CLOSED      -> 0;
            case OPEN        -> 1;
            case HALF_OPEN   -> 2;
            case FORCED_OPEN -> 1;   // treat forced-open same as open for alerting
            case DISABLED    -> 0;   // disabled behaves like closed (calls allowed)
            case METRICS_ONLY -> 0;  // observing only, not blocking
        };
    }
}
