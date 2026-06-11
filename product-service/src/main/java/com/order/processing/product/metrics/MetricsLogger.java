package com.order.processing.product.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Periodically logs a structured snapshot of all product-service stock-reservation
 * metrics to the application log, providing a "log-based" export channel that works
 * even when no Prometheus scraper is attached.
 *
 * <h3>Metrics logged</h3>
 * <pre>
 *   stock_reservations_total[outcome=success]             — successful reservations
 *   stock_reservations_total[outcome=insufficient_stock]  — stock-shortage failures
 *   stock_reservations_total[outcome=product_not_found]   — missing product failures
 *   stock_reservations_total[outcome=skipped]             — idempotency-skipped events
 *   stock_reservations_total[outcome=error]               — unexpected errors
 *   stock_reservation_duration_seconds                    — p50 / p95 / p99 latency
 * </pre>
 *
 * <h3>Log format</h3>
 * All lines are prefixed with {@code [METRICS]} for easy log-aggregator filtering:
 * <pre>
 *   grep '\[METRICS\]' /var/log/product-service.log
 * </pre>
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class MetricsLogger {

    private final MeterRegistry registry;

    /**
     * Emits the metrics snapshot every 60 seconds after an initial 30-second delay.
     * Both intervals are overridable via application properties:
     * <ul>
     *   <li>{@code metrics.log.initial-delay-ms} (default 30000)</li>
     *   <li>{@code metrics.log.interval-ms}       (default 60000)</li>
     * </ul>
     */
    @Scheduled(initialDelayString = "${metrics.log.initial-delay-ms:30000}",
               fixedDelayString   = "${metrics.log.interval-ms:60000}")
    public void logMetricsSnapshot() {
        log.info("[METRICS] ══════════════════ PRODUCT-SERVICE METRICS SNAPSHOT ══════════════════");

        // ── Stock reservation outcome counters ────────────────────────────────
        logCounter("stock_reservations_total", "outcome", "success",             "stock.reservations.success");
        logCounter("stock_reservations_total", "outcome", "insufficient_stock",  "stock.reservations.insufficient_stock");
        logCounter("stock_reservations_total", "outcome", "product_not_found",   "stock.reservations.product_not_found");
        logCounter("stock_reservations_total", "outcome", "skipped",             "stock.reservations.skipped");
        logCounter("stock_reservations_total", "outcome", "error",               "stock.reservations.error");

        // ── Reservation latency timer ─────────────────────────────────────────
        logTimer("stock_reservation_duration_seconds", "stock.reservation.duration");

        log.info("[METRICS] ═══════════════════════════════════════════════════════════════════════");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
}
