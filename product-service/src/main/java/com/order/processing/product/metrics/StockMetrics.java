package com.order.processing.product.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for the product-service stock reservation domain.
 *
 * <h3>Metric catalogue</h3>
 * <pre>
 * Metric name                        Type     Tags              Description
 * ──────────────────────────────────  ──────   ──────────────    ──────────────────────────────────────
 * stock_reservations_total           Counter  outcome           Total reservation attempts by outcome.
 *                                             outcome=success   Stock reduced, ProductReservedEvent sent.
 *                                             outcome=insufficient_stock  Not enough stock available.
 *                                             outcome=product_not_found   ProductId does not exist.
 *                                             outcome=skipped   Duplicate event (idempotency guard hit).
 *                                             outcome=error     Unexpected exception.
 *
 * stock_reservation_duration_seconds Timer    -                 Wall-clock time of handleOrderPlaced()
 *                                                               from idempotency check to event publish.
 * </pre>
 *
 * <h3>Prometheus query examples</h3>
 * <pre>
 *   # Stock reservation success rate
 *   rate(stock_reservations_total{outcome="success"}[5m])
 *     / rate(stock_reservations_total[5m])
 *
 *   # Insufficient-stock failure rate (business SLO alert)
 *   rate(stock_reservations_total{outcome="insufficient_stock"}[5m]) > 0.1
 *
 *   # p95 reservation latency
 *   histogram_quantile(0.95,
 *     rate(stock_reservation_duration_seconds_bucket[5m]))
 * </pre>
 */
@Component
public class StockMetrics {

    private final Counter successCounter;
    private final Counter insufficientStockCounter;
    private final Counter productNotFoundCounter;
    private final Counter skippedCounter;
    private final Counter errorCounter;
    private final Timer   reservationTimer;

    public StockMetrics(MeterRegistry registry) {

        this.successCounter = Counter.builder("stock_reservations_total")
                .description("Total stock reservation attempts by outcome")
                .tag("outcome", "success")
                .register(registry);

        this.insufficientStockCounter = Counter.builder("stock_reservations_total")
                .description("Total stock reservation attempts by outcome")
                .tag("outcome", "insufficient_stock")
                .register(registry);

        this.productNotFoundCounter = Counter.builder("stock_reservations_total")
                .description("Total stock reservation attempts by outcome")
                .tag("outcome", "product_not_found")
                .register(registry);

        this.skippedCounter = Counter.builder("stock_reservations_total")
                .description("Total stock reservation attempts by outcome")
                .tag("outcome", "skipped")
                .register(registry);

        this.errorCounter = Counter.builder("stock_reservations_total")
                .description("Total stock reservation attempts by outcome")
                .tag("outcome", "error")
                .register(registry);

        this.reservationTimer = Timer.builder("stock_reservation_duration_seconds")
                .description("End-to-end duration of OrderPlacedEvent handling " +
                              "(idempotency check → stock deduction → event publish)")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordSuccess()           { successCounter.increment();           }
    public void recordInsufficientStock() { insufficientStockCounter.increment(); }
    public void recordProductNotFound()   { productNotFoundCounter.increment();   }
    public void recordSkipped()           { skippedCounter.increment();           }
    public void recordError()             { errorCounter.increment();             }

    public Timer reservationTimer() { return reservationTimer; }
}
