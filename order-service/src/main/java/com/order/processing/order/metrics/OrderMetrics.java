package com.order.processing.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central Micrometer metrics registry for the order-service.
 *
 * <p>All meters are registered eagerly at construction time so they appear in
 * {@code /actuator/prometheus} with a zero value from the moment the service
 * starts — before the first request arrives.  Prometheus scraping rules rely on
 * metric names being stable and present from startup.
 *
 * <h3>Metric catalogue</h3>
 *
 * <table border="1">
 *   <tr><th>Metric name</th><th>Type</th><th>Key tags</th><th>Description</th></tr>
 *   <tr>
 *     <td>{@code orders_placed_total}</td>
 *     <td>Counter</td>
 *     <td>-</td>
 *     <td>Every order successfully persisted and published to Kafka.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code orders_placed_failed_total}</td>
 *     <td>Counter</td>
 *     <td>-</td>
 *     <td>Order creation attempts that threw an exception before persisting.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code order_create_duration_seconds}</td>
 *     <td>Timer</td>
 *     <td>-</td>
 *     <td>Wall-clock time of the synchronous part of {@code createOrder()}:
 *         product fetch + stock check + DB write + Kafka publish.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code saga_step_duration_seconds}</td>
 *     <td>Timer</td>
 *     <td>{@code step}, {@code outcome}</td>
 *     <td>Duration of each individual saga event-handler invocation.
 *         {@code step} ∈ {product_reserved, product_reservation_failed,
 *         payment_completed, payment_failed}.
 *         {@code outcome} ∈ {success, skipped, error}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code saga_orders_inflight}</td>
 *     <td>Gauge</td>
 *     <td>-</td>
 *     <td>Current number of orders in a non-terminal state (PENDING only in this
 *         in-memory approximation). Incremented on createOrder, decremented on
 *         any terminal saga outcome.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code saga_outcome_total}</td>
 *     <td>Counter</td>
 *     <td>{@code outcome}</td>
 *     <td>Terminal saga outcomes.
 *         {@code outcome} ∈ {confirmed, cancelled, failed}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code circuit_breaker_state}</td>
 *     <td>Gauge</td>
 *     <td>{@code name}</td>
 *     <td>Numeric encoding of Resilience4j CB state:
 *         0 = CLOSED, 1 = OPEN, 2 = HALF_OPEN.
 *         Recorded by {@link CircuitBreakerMetrics}.</td>
 *   </tr>
 * </table>
 *
 * <h3>Prometheus endpoint</h3>
 * <pre>
 *   GET /actuator/prometheus
 * </pre>
 *
 * <h3>Example Prometheus queries</h3>
 * <pre>
 *   # Order placement rate (per-minute average over last 5 min)
 *   rate(orders_placed_total[5m]) * 60
 *
 *   # Saga success rate
 *   rate(saga_outcome_total{outcome="confirmed"}[5m])
 *     / rate(orders_placed_total[5m])
 *
 *   # 95th-percentile order creation latency
 *   histogram_quantile(0.95, rate(order_create_duration_seconds_bucket[5m]))
 *
 *   # Orders stuck in PENDING (non-zero means saga is lagging)
 *   saga_orders_inflight
 * </pre>
 */
@Component
public class OrderMetrics {

    // ── Counters ─────────────────────────────────────────────────────────────

    /** Incremented once per successful {@code createOrder()} call. */
    private final Counter ordersPlacedCounter;

    /** Incremented when {@code createOrder()} throws before persisting. */
    private final Counter ordersFailedCounter;

    /** Incremented when a saga completes with status CONFIRMED. */
    private final Counter sagaConfirmedCounter;

    /** Incremented when a saga completes with status CANCELLED. */
    private final Counter sagaCancelledCounter;

    /** Incremented when a saga completes with status FAILED. */
    private final Counter sagaFailedCounter;

    // ── Timers ───────────────────────────────────────────────────────────────

    /**
     * Measures the synchronous part of order creation:
     * product fetch + stock check + DB INSERT + Kafka publish.
     */
    private final Timer orderCreateTimer;

    // ── In-flight Gauge backing ───────────────────────────────────────────────

    /**
     * Atomic integer backing the {@code saga_orders_inflight} gauge.
     * Incremented on createOrder(), decremented when a terminal saga event
     * (CONFIRMED / CANCELLED / FAILED) is processed.
     */
    private final AtomicInteger inflightOrders = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor — registers all meters
    // ─────────────────────────────────────────────────────────────────────────

    public OrderMetrics(MeterRegistry registry) {

        // ── Counters ─────────────────────────────────────────────────────────
        this.ordersPlacedCounter = Counter.builder("orders_placed_total")
                .description("Total number of orders successfully placed and published to Kafka")
                .register(registry);

        this.ordersFailedCounter = Counter.builder("orders_placed_failed_total")
                .description("Total number of order creation attempts that failed before persisting")
                .register(registry);

        this.sagaConfirmedCounter = Counter.builder("saga_outcome_total")
                .description("Terminal saga outcomes by type")
                .tag("outcome", "confirmed")
                .register(registry);

        this.sagaCancelledCounter = Counter.builder("saga_outcome_total")
                .description("Terminal saga outcomes by type")
                .tag("outcome", "cancelled")
                .register(registry);

        this.sagaFailedCounter = Counter.builder("saga_outcome_total")
                .description("Terminal saga outcomes by type")
                .tag("outcome", "failed")
                .register(registry);

        // ── Timers ────────────────────────────────────────────────────────────
        this.orderCreateTimer = Timer.builder("order_create_duration_seconds")
                .description("Duration of the synchronous portion of order creation " +
                              "(product validation + DB write + Kafka publish)")
                .publishPercentiles(0.50, 0.95, 0.99)   // expose p50/p95/p99 in Prometheus
                .publishPercentileHistogram()             // enables histogram_quantile() in PromQL
                .register(registry);

        // ── In-flight gauge ───────────────────────────────────────────────────
        // Gauge.builder wraps the AtomicInteger by reference — the value is
        // always current when Prometheus scrapes /actuator/prometheus.
        io.micrometer.core.instrument.Gauge.builder("saga_orders_inflight", inflightOrders, AtomicInteger::get)
                .description("Number of orders currently in non-terminal PENDING state " +
                              "(approximation based on saga start/end events in this JVM)")
                .register(registry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called from OrderServiceImpl and SagaEventListener
    // ─────────────────────────────────────────────────────────────────────────

    /** Records a successful order placement. */
    public void incrementOrdersPlaced() {
        ordersPlacedCounter.increment();
        inflightOrders.incrementAndGet();
    }

    /** Records a failed order placement attempt. */
    public void incrementOrdersFailed() {
        ordersFailedCounter.increment();
    }

    /** Records the end-to-end duration of the synchronous order-creation path. */
    public Timer orderCreateTimer() {
        return orderCreateTimer;
    }

    /**
     * Returns a {@link Timer} for a specific saga step and outcome, creating it
     * lazily if it does not exist yet.
     *
     * <p>Tags used:
     * <ul>
     *   <li>{@code step}    — e.g. {@code product_reserved}, {@code payment_completed}</li>
     *   <li>{@code outcome} — {@code success}, {@code skipped}, or {@code error}</li>
     * </ul>
     *
     * @param registry the meter registry (must be the same instance used at construction)
     * @param step     the saga step name
     * @param outcome  the processing outcome
     * @return a Timer that accumulates duration samples for this step/outcome pair
     */
    public static Timer sagaStepTimer(MeterRegistry registry, String step, String outcome) {
        return Timer.builder("saga_step_duration_seconds")
                .description("Duration of each saga event-handler invocation")
                .tag("step", step)
                .tag("outcome", outcome)
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    // ── Saga terminal outcomes ────────────────────────────────────────────────

    /** Records a CONFIRMED terminal outcome and decrements the in-flight gauge. */
    public void recordSagaConfirmed() {
        sagaConfirmedCounter.increment();
        inflightOrders.decrementAndGet();
    }

    /** Records a CANCELLED terminal outcome and decrements the in-flight gauge. */
    public void recordSagaCancelled() {
        sagaCancelledCounter.increment();
        inflightOrders.decrementAndGet();
    }

    /** Records a FAILED terminal outcome and decrements the in-flight gauge. */
    public void recordSagaFailed() {
        sagaFailedCounter.increment();
        inflightOrders.decrementAndGet();
    }
}
