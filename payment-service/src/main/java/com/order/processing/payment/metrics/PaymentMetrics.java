package com.order.processing.payment.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for the payment-service domain.
 *
 * <h3>Metric catalogue</h3>
 * <pre>
 * Metric name                      Type     Tags             Description
 * ──────────────────────────────   ──────   ─────────────    ────────────────────────────────────────
 * payments_processed_total         Counter  outcome          Total payment attempts by outcome.
 *                                           outcome=success  Payment charged and persisted.
 *                                           outcome=declined Payment gateway declined (simulated).
 *                                           outcome=error    Unexpected exception during processing.
 *                                           outcome=skipped  Duplicate event (idempotency guard hit).
 *
 * payment_processing_duration_     Timer    outcome          Wall-clock time of handlePaymentInitiated()
 *   seconds                                                  from idempotency check to event publish.
 * </pre>
 *
 * <h3>Prometheus query examples</h3>
 * <pre>
 *   # Payment success rate over the last 5 minutes
 *   rate(payments_processed_total{outcome="success"}[5m])
 *     / rate(payments_processed_total[5m])
 *
 *   # p95 payment processing latency
 *   histogram_quantile(0.95,
 *     rate(payment_processing_duration_seconds_bucket[5m]))
 *
 *   # Payment decline rate
 *   rate(payments_processed_total{outcome="declined"}[5m])
 * </pre>
 */
@Component
public class PaymentMetrics {

    private final Counter successCounter;
    private final Counter declinedCounter;
    private final Counter errorCounter;
    private final Counter skippedCounter;
    private final Timer   processingTimer;

    public PaymentMetrics(MeterRegistry registry) {

        this.successCounter = Counter.builder("payments_processed_total")
                .description("Total payment processing attempts by outcome")
                .tag("outcome", "success")
                .register(registry);

        this.declinedCounter = Counter.builder("payments_processed_total")
                .description("Total payment processing attempts by outcome")
                .tag("outcome", "declined")
                .register(registry);

        this.errorCounter = Counter.builder("payments_processed_total")
                .description("Total payment processing attempts by outcome")
                .tag("outcome", "error")
                .register(registry);

        this.skippedCounter = Counter.builder("payments_processed_total")
                .description("Total payment processing attempts by outcome")
                .tag("outcome", "skipped")
                .register(registry);

        this.processingTimer = Timer.builder("payment_processing_duration_seconds")
                .description("End-to-end duration of PaymentInitiatedEvent handling " +
                              "(idempotency check → gateway call → event publish)")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordSuccess()  { successCounter.increment();  }
    public void recordDeclined() { declinedCounter.increment(); }
    public void recordError()    { errorCounter.increment();    }
    public void recordSkipped()  { skippedCounter.increment();  }

    /** Returns the timer so callers can use {@code Timer.Sample} for precise measurement. */
    public Timer processingTimer() { return processingTimer; }
}
