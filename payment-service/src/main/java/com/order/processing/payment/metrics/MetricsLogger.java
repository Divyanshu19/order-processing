package com.order.processing.payment.metrics;

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
 * Periodically logs a structured snapshot of all payment-service business metrics
 * to the application log, providing an alternative "log-based" export channel
 * alongside the Prometheus {@code /actuator/prometheus} endpoint.
 *
 * <h3>Metrics logged</h3>
 * <pre>
 *   payments_processed_total[outcome=success]   — successful payment count
 *   payments_processed_total[outcome=declined]  — declined payments
 *   payments_processed_total[outcome=error]     — unexpected processing errors
 *   payments_processed_total[outcome=skipped]   — idempotency-skipped duplicates
 *   payment_processing_duration_seconds         — p50 / p95 / p99 latency
 * </pre>
 *
 * <h3>Log format</h3>
 * All lines are prefixed with {@code [METRICS]} for easy log-aggregator filtering:
 * <pre>
 *   grep '\[METRICS\]' /var/log/payment-service.log
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
        log.info("[METRICS] ══════════════════ PAYMENT-SERVICE METRICS SNAPSHOT ══════════════════");

        // ── Payment outcome counters ──────────────────────────────────────────
        logCounter("payments_processed_total", "outcome", "success",  "payments.success");
        logCounter("payments_processed_total", "outcome", "declined", "payments.declined");
        logCounter("payments_processed_total", "outcome", "error",    "payments.error");
        logCounter("payments_processed_total", "outcome", "skipped",  "payments.skipped");

        // ── Processing latency timer ─────────────────────────────────────────
        logTimer("payment_processing_duration_seconds", "payment.processing.duration");

        log.info("[METRICS] ════════════════════════════════════════════════════════════════════════");
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
