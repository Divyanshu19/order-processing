package com.order.processing.product.event;

import com.order.processing.product.dto.ProductResponse;
import com.order.processing.product.dto.StockUpdateRequest;
import com.order.processing.product.exception.InsufficientStockException;
import com.order.processing.product.exception.ProductNotFoundException;
import com.order.processing.product.idempotency.ProcessedEvent;
import com.order.processing.product.idempotency.ProcessedEventRepository;
import com.order.processing.product.metrics.StockMetrics;
import com.order.processing.product.service.ProductService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Consumes {@code OrderPlacedEvent} messages from the {@code order-placed} topic
 * and attempts to reserve (reduce) stock for the requested product.
 *
 * <p>This is the second step of the order-processing saga:
 *
 * <pre>
 *  [order-placed]  →  reserveStock()
 *                         │
 *               ┌─────────┴──────────┐
 *               ▼                    ▼
 *    stock reduced OK          stock not available / product not found
 *  [product-reserved]        [product-reservation-failed]
 *         │                           │
 *    order-service               order-service
 *  initiates payment           marks order CANCELLED
 * </pre>
 *
 * <p>Both the happy path and all failure cases publish a compensating event so
 * the saga always advances — no message is ever silently dropped.
 *
 * <h3>Idempotency</h3>
 * <p>Kafka's at-least-once delivery guarantee means a message can arrive more
 * than once (e.g. after a consumer restart or a broker-side retry). Without a
 * guard this would deduct stock twice for the same order.
 *
 * <p>Protection strategy:
 * <ol>
 *   <li>At the start of {@link #handleOrderPlaced} check the
 *       {@code processed_events} table for a row with
 *       {@code event_type = "ORDER_PLACED"} and the same {@code orderId}.</li>
 *   <li>If a row already exists → log and return immediately (no-op).</li>
 *   <li>If no row exists → record one <em>inside the same DB transaction</em>
 *       that deducts stock, making the check-and-record atomic.</li>
 *   <li>If two threads race on the same duplicate, the unique DB constraint
 *       causes one of them to receive a {@link DataIntegrityViolationException},
 *       which is caught and treated as a duplicate skip.</li>
 * </ol>
 *
 * <p>Consumer group: {@code product-service-group} (set in {@code application.yml}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPlacedEventListener {

    /** Stable event-type key stored in the idempotency table. */
    private static final String EVENT_TYPE = "ORDER_PLACED";

    private final ProductService productService;
    private final ProductEventPublisher productEventPublisher;
    private final ProcessedEventRepository processedEventRepository;
    private final StockMetrics stockMetrics;
    private final MeterRegistry meterRegistry;

    /**
     * Handles an incoming {@link OrderPlacedEvent}.
     *
     * <p>Processing steps:
     * <ol>
     *   <li><strong>Idempotency check</strong> — skip if already processed.</li>
     *   <li>Attempt to reduce stock via {@link ProductService#reduceStock}.</li>
     *   <li>On success  → build and publish {@link ProductReservedEvent}.</li>
     *   <li>On {@link ProductNotFoundException}  → publish
     *       {@link ProductReservationFailedEvent} with reason "Product not found".</li>
     *   <li>On {@link InsufficientStockException} → publish
     *       {@link ProductReservationFailedEvent} with reason "Insufficient stock".</li>
     *   <li>On any unexpected error → publish
     *       {@link ProductReservationFailedEvent} with reason containing the
     *       exception message so the saga is never left in a dangling state.</li>
     * </ol>
     *
     * @param event the deserialized event from the {@code order-placed} topic
     */
    @Transactional
    @KafkaListener(
            topics = "${kafka.topics.order-placed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleOrderPlaced(OrderPlacedEvent event) {
        log.info("[IDEMPOTENCY] Received OrderPlacedEvent: orderId={}, productId={}, quantity={}",
                event.getOrderId(), event.getProductId(), event.getQuantity());

        Timer.Sample sample = Timer.start(meterRegistry);

        // ── Idempotency guard ─────────────────────────────────────────────────
        if (processedEventRepository.existsByEventTypeAndOrderId(EVENT_TYPE, event.getOrderId())) {
            log.warn("[IDEMPOTENCY] Duplicate OrderPlacedEvent detected — skipping. " +
                     "orderId={} was already processed.", event.getOrderId());
            stockMetrics.recordSkipped();
            sample.stop(stockMetrics.reservationTimer());
            return;
        }

        try {
            // ── Record event as processed (atomic with stock deduction) ───────
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventType(EVENT_TYPE)
                    .orderId(event.getOrderId())
                    .build());

            // ── Step 1: Attempt stock reduction ──────────────────────────────
            StockUpdateRequest stockUpdateRequest = StockUpdateRequest.builder()
                    .quantity(event.getQuantity())
                    .build();

            ProductResponse updatedProduct = productService.reduceStock(
                    event.getProductId(), stockUpdateRequest);

            log.info("[SAGA] Stock reserved for orderId={}, productId={}, remainingStock={}",
                    event.getOrderId(), event.getProductId(), updatedProduct.getStockQuantity());

            // ── Step 2: Publish success event ─────────────────────────────────
            ProductReservedEvent reservedEvent = ProductReservedEvent.builder()
                    .orderId(event.getOrderId())
                    .productId(event.getProductId())
                    .quantity(event.getQuantity())
                    .remainingStock(updatedProduct.getStockQuantity())
                    .reservedAt(LocalDateTime.now())
                    .build();

            productEventPublisher.publishProductReserved(reservedEvent);
            stockMetrics.recordSuccess();
            sample.stop(stockMetrics.reservationTimer());

        } catch (DataIntegrityViolationException ex) {
            // ── Concurrent duplicate delivery hit the unique constraint ───────
            log.warn("[IDEMPOTENCY] Concurrent duplicate OrderPlacedEvent for orderId={} — skipping.",
                    event.getOrderId());
            stockMetrics.recordSkipped();
            sample.stop(stockMetrics.reservationTimer());

        } catch (ProductNotFoundException ex) {
            // ── Step 3a: Product does not exist ───────────────────────────────
            log.warn("[SAGA] Reservation failed — product not found: orderId={}, productId={}",
                    event.getOrderId(), event.getProductId());
            publishFailure(event, 0, "Product not found: " + ex.getMessage());
            stockMetrics.recordProductNotFound();
            sample.stop(stockMetrics.reservationTimer());

        } catch (InsufficientStockException ex) {
            // ── Step 3b: Stock available but not enough ───────────────────────
            log.warn("[SAGA] Reservation failed — insufficient stock: orderId={}, productId={}, quantity={}",
                    event.getOrderId(), event.getProductId(), event.getQuantity());
            publishFailure(event, 0, "Insufficient stock: " + ex.getMessage());
            stockMetrics.recordInsufficientStock();
            sample.stop(stockMetrics.reservationTimer());

        } catch (Exception ex) {
            // ── Step 3c: Unexpected error — saga must still advance ───────────
            log.error("[SAGA] Unexpected error while reserving stock for orderId={}, productId={}: {}",
                    event.getOrderId(), event.getProductId(), ex.getMessage(), ex);
            publishFailure(event, 0, "Unexpected error: " + ex.getMessage());
            stockMetrics.recordError();
            sample.stop(stockMetrics.reservationTimer());
        }
    }

    // ── Private helper ────────────────────────────────────────────────────────

    /**
     * Builds and publishes a {@link ProductReservationFailedEvent} so the saga
     * always has a terminal compensating event to act on.
     */
    private void publishFailure(OrderPlacedEvent event, int availableStock, String reason) {
        ProductReservationFailedEvent failedEvent = ProductReservationFailedEvent.builder()
                .orderId(event.getOrderId())
                .productId(event.getProductId())
                .requestedQuantity(event.getQuantity())
                .availableStock(availableStock)
                .reason(reason)
                .failedAt(LocalDateTime.now())
                .build();

        productEventPublisher.publishProductReservationFailed(failedEvent);
    }
}
