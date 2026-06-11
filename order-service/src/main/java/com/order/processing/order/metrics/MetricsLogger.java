package com.order.processing.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Periodically logs a human-readable and machine-parseable snapshot of every
 * custom business metric to the application log.
 *
 * <h3>Why log metrics at all when Prometheus exists?</h3>
 * <ul>
 *   <li>Metrics are immediately visible in any log aggregator
 *       (ELK, Splunk, CloudWatch Logs) without needing a separate Prometheus
 *       scrape pipeline.</li>
 *   <li>Ops teams can grep the service log for {@code [METRICS]} and see a
 *       rolling health snapshot without opening Grafana.</li>
 *   <li>Useful in local development where Prometheus is not running.</li>
 * </ul>
 *
 * <h3>Log format</h3>
 * Every line is prefixed with {@code [METRICS]} so it can be isolated:
 * <pre>
 *   grep '\[METRICS\]' /var/log/order-service.log
 * </pre>
 *
 * <h3>Scheduling</h3>
 * Runs every 60 seconds (configurable via {@code metrics.log.interval-ms}).
 * The first execution is delayed by 30 seconds to allow the application to
 * fully start before emitting zeroes.
 *
 * <h3>Metrics logged</h3>
 * <pre>
 *   orders_placed_total            — cumulative order placements
 *   orders_placed_failed_total     — cumulative failed placements
 *   saga_orders_inflight           — current PENDING orders (gauge)
 *   saga_outcome_total[confirmed]  — cumulative confirmed sagas
 *   saga_outcome_total[cancelled]  — cumulative cancelled sagas
 *   saga_outcome_total[failed]     — cumulative failed sagas
 *   order_create_duration_seconds  — p50 / p95 / p99 / count
 *   saga_step_duration_seconds     — p95 per step+outcome
 *   circuit_breaker_state          — per CB name: 0=CLOSED 1=OPEN 2=HALF_OPEN
 *   circuit_breaker_transitions_total — state transition counts
 * </pre>
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class MetricsLogger {

    private final MeterRegistry registry;

    /**
     * Emits a full metrics snapshot to the log every 60 seconds.
     * Initial delay of 30 s prevents a flood of zeroes during startup.
     */
    @Scheduled(initialDelayString  = "${metrics.log.initial-delay-ms:30000}",
               fixedDelayString    = "${metrics.log.interval-ms:60000}")
    public void logMetricsSnapshot() {
        log.info("[METRICS] ════════════════════ ORDER-SERVICE METRICS SNAPSHOT ════════════════════");

        // ── Order placement counters ──────────────────────────────────────────
        logCounter("orders_placed_total",        "orders.placed");
        logCounter("orders_placed_failed_total",  "orders.failed");

        // ── In-flight gauge ───────────────────────────────────────────────────
        logGauge("saga_orders_inflight", "saga.inflight");

        // ── Saga terminal outcome counters ────────────────────────────────────
        logCounter("saga_outcome_total", "outcome", "confirmed", "saga.outcome.confirmed");
        logCounter("saga_outcome_total", "outcome", "cancelled",  "saga.outcome.cancelled");
        logCounter("saga_outcome_total", "outcome", "failed",     "saga.outcome.failed");

        // ── Order creation timer ──────────────────────────────────────────────
        logTimer("order_create_duration_seconds", "order.create.duration");

        // ── Saga step timers (log p95 for every step × outcome combination) ──
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("saga_step_duration_seconds"))
                .filter(m -> m instanceof Timer)
                .map(m -> (Timer) m)
                .forEach(timer -> {
                    String step    = timer.getId().getTag("step");
                    String outcome = timer.getId().getTag("outcome");
                    double p95     = timer.percentile(0.95, TimeUnit.MILLISECONDS);
                    double p50     = timer.percentile(0.50, TimeUnit.MILLISECONDS);
                    log.info("[METRICS]   saga_step_duration_seconds{{step={}, outcome={}}} " +
                             "count={} p50={}ms p95={}ms",
                             step, outcome, timer.count(),
                             String.format("%.2f", p50),
                             String.format("%.2f", p95));
                });

        // ── Circuit breaker state gauges ─────────────────────────────────────
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("circuit_breaker_state"))
                .filter(m -> m instanceof Gauge)
                .map(m -> (Gauge) m)
                .forEach(gauge -> {
                    String name  = gauge.getId().getTag("name");
                    double value = gauge.value();
                    String label = encodeStateLabel(value);
                    log.info("[METRICS]   circuit_breaker_state{{name={}}} = {} ({})",
                             name, (int) value, label);
                });

        // ── Circuit breaker transition counters ───────────────────────────────
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("circuit_breaker_transitions_total"))
                .filter(m -> m instanceof Counter)
                .map(m -> (Counter) m)
                .forEach(counter -> {
                    String cbName = counter.getId().getTag("name");
                    String from   = counter.getId().getTag("from");
                    String to     = counter.getId().getTag("to");
                    log.info("[METRICS]   circuit_breaker_transitions_total{{name={}, from={}, to={}}} = {}",
                             cbName, from, to, (long) counter.count());
                });

        log.info("[METRICS] ═══════════════════════════════════════════════════════════════════════");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Logs a counter by name (no tag filter). */
    private void logCounter(String meterName, String label) {
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(meterName) && m instanceof Counter)
                .map(m -> (Counter) m)
                .findFirst()
                .ifPresentOrElse(
                        c -> log.info("[METRICS]   {} = {}", label, (long) c.count()),
                        () -> log.debug("[METRICS]   {} = (not yet registered)", label));
    }

    /** Logs a counter filtered by a single tag value. */
    private void logCounter(String meterName, String tagKey, String tagValue, String label) {
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(meterName)
                          && tagValue.equals(m.getId().getTag(tagKey))
                          && m instanceof Counter)
                .map(m -> (Counter) m)
                .findFirst()
                .ifPresentOrElse(
                        c -> log.info("[METRICS]   {} = {}", label, (long) c.count()),
                        () -> log.debug("[METRICS]   {} = (not yet registered)", label));
    }

    /** Logs a gauge by name. */
    private void logGauge(String meterName, String label) {
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(meterName) && m instanceof Gauge)
                .map(m -> (Gauge) m)
                .findFirst()
                .ifPresentOrElse(
                        g -> log.info("[METRICS]   {} = {}", label, (long) g.value()),
                        () -> log.debug("[METRICS]   {} = (not yet registered)", label));
    }

    /** Logs a timer's count, p50, p95, and p99 in milliseconds. */
    private void logTimer(String meterName, String label) {
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(meterName) && m instanceof Timer)
                .map(m -> (Timer) m)
                .findFirst()
                .ifPresentOrElse(t -> log.info(
                        "[METRICS]   {} count={} p50={}ms p95={}ms p99={}ms",
                        label,
                        t.count(),
                        String.format("%.2f", t.percentile(0.50, TimeUnit.MILLISECONDS)),
                        String.format("%.2f", t.percentile(0.95, TimeUnit.MILLISECONDS)),
                        String.format("%.2f", t.percentile(0.99, TimeUnit.MILLISECONDS))),
                        () -> log.debug("[METRICS]   {} = (not yet registered)", label));
    }

    /** Maps the numeric circuit breaker state to a human-readable label. */
    private String encodeStateLabel(double value) {
        return switch ((int) value) {
            case 0  -> "CLOSED";
            case 1  -> "OPEN";
            case 2  -> "HALF_OPEN";
            default -> "UNKNOWN";
        };
    }
}
