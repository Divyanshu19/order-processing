package com.order.processing.order.event;

import com.order.processing.order.dto.OrderResponse;
import com.order.processing.order.entity.Order.OrderStatus;
import com.order.processing.order.metrics.OrderMetrics;
import com.order.processing.order.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Consumes downstream saga events and drives the order through its lifecycle.
 *
 * <pre>
 * Topic                         Action
 * ──────────────────────────    ───────────────────────────────────────────────
 * product-reserved              Build + publish PaymentInitiatedEvent
 * product-reservation-failed    Mark order CANCELLED (compensating transaction)
 * payment-completed             Mark order CONFIRMED  (saga happy path end)
 * payment-failed                Mark order FAILED     (compensating transaction)
 * </pre>
 *
 * <h3>Idempotency</h3>
 * <p>Every handler reads the current order status from the DB <em>before</em>
 * applying a state transition. This guards against duplicate Kafka deliveries
 * (at-least-once semantics) that would otherwise re-run the same transition
 * and potentially corrupt the order state or publish extra downstream events.
 *
 * <p>The check-then-update is inherently safe here because:
 * <ul>
 *   <li>Each order has only one active saga path at any moment.</li>
 *   <li>Once an order reaches a terminal state (CONFIRMED / CANCELLED / FAILED)
 *       all subsequent duplicate events are no-ops.</li>
 *   <li>{@link OrderServiceImpl#updateOrderStatus} is already @Transactional,
 *       providing optimistic isolation for the read-modify-write cycle.</li>
 * </ul>
 *
 * <p>Consumer group: {@code order-service-group} (set in {@code application.yml}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaEventListener {

    /** States from which no further saga transitions are allowed. */
    private static final Set<OrderStatus> TERMINAL_STATUSES =
            EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, OrderStatus.FAILED);

    private final OrderService orderService;
    private final OrderEventPublisher orderEventPublisher;
    private final MeterRegistry meterRegistry;
    private final OrderMetrics orderMetrics;

    // ── 1. product-reserved → publish PaymentInitiatedEvent ──────────────────

    /**
     * Consumes a {@link ProductReservedEvent}.
     *
     * <p>Stock has been successfully reserved; the saga advances by publishing
     * a {@link PaymentInitiatedEvent} to trigger the payment-service.
     *
     * <p><strong>Idempotency:</strong> only acts when the order is still
     * {@code PENDING}. If it has already moved to any other status (e.g. because
     * this event was re-delivered after the payment event already arrived), the
     * handler logs a warning and returns without publishing a second
     * {@code payment-initiated} event.
     *
     * @param event the inbound event carrying orderId, productId, quantity, etc.
     */
    @KafkaListener(
            topics = "${kafka.topics.product-reserved}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleProductReserved(ProductReservedEvent event) {
        log.info("[IDEMPOTENCY] Received ProductReservedEvent: orderId={}, productId={}, remainingStock={}",
                event.getOrderId(), event.getProductId(), event.getRemainingStock());

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Fetch saved order to recover userId, totalPrice, paymentMethod,
            // and — critically — the current status for the idempotency check.
            OrderResponse order = orderService.getOrderById(event.getOrderId());

            if (order.getStatus() != OrderStatus.PENDING) {
                log.warn("[IDEMPOTENCY] Skipping ProductReservedEvent for orderId={} — " +
                         "order status is already '{}', expected PENDING.",
                         event.getOrderId(), order.getStatus());
                sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "product_reserved", "skipped"));
                return;
            }

            PaymentInitiatedEvent paymentEvent = PaymentInitiatedEvent.builder()
                    .orderId(event.getOrderId())
                    .userId(order.getUserId())
                    .amount(order.getTotalPrice())
                    .paymentMethod(order.getPaymentMethod())
                    .initiatedAt(LocalDateTime.now())
                    .build();

            log.info("[SAGA] Publishing PaymentInitiatedEvent for orderId={}", event.getOrderId());
            orderEventPublisher.publishPaymentInitiated(paymentEvent);
            sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "product_reserved", "success"));

        } catch (Exception ex) {
            log.error("[SAGA] Error handling ProductReservedEvent for orderId={}: {}",
                    event.getOrderId(), ex.getMessage(), ex);
            sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "product_reserved", "error"));
        }
    }

    // ── 2. product-reservation-failed → CANCELLED ────────────────────────────

    /**
     * Consumes a {@link ProductReservationFailedEvent}.
     *
     * <p>Stock could not be reserved — the order is marked {@code CANCELLED}
     * as the compensating transaction for this saga step.
     *
     * <p><strong>Idempotency:</strong> only applies the transition when the
     * order is still {@code PENDING}. Duplicate deliveries after the order is
     * already in a terminal state are silently discarded.
     *
     * @param event the inbound event carrying orderId, reason, etc.
     */
    @KafkaListener(
            topics = "${kafka.topics.product-reservation-failed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleProductReservationFailed(ProductReservationFailedEvent event) {
        log.warn("[IDEMPOTENCY] Received ProductReservationFailedEvent: orderId={}, reason={}",
                event.getOrderId(), event.getReason());

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            OrderResponse order = orderService.getOrderById(event.getOrderId());

            if (order.getStatus() != OrderStatus.PENDING) {
                log.warn("[IDEMPOTENCY] Skipping ProductReservationFailedEvent for orderId={} — " +
                         "order status is already '{}', expected PENDING.",
                         event.getOrderId(), order.getStatus());
                sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "product_reservation_failed", "skipped"));
                return;
            }

            orderService.updateOrderStatus(event.getOrderId(), OrderStatus.CANCELLED);
            orderMetrics.recordSagaCancelled();
            log.info("[SAGA] Order CANCELLED due to reservation failure: orderId={}", event.getOrderId());
            sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "product_reservation_failed", "success"));

        } catch (Exception ex) {
            log.error("[SAGA] Error handling ProductReservationFailedEvent for orderId={}: {}",
                    event.getOrderId(), ex.getMessage(), ex);
            sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "product_reservation_failed", "error"));
        }
    }

    // ── 3. payment-completed → CONFIRMED ─────────────────────────────────────

    /**
     * Consumes a {@link PaymentCompletedEvent}.
     *
     * <p>Payment was charged successfully — the order is marked {@code CONFIRMED},
     * which is the final happy-path state of the saga.
     *
     * <p><strong>Idempotency:</strong> skips if the order is already in a
     * terminal state — prevents a re-delivered event from overwriting
     * {@code FAILED} with {@code CONFIRMED}.
     *
     * @param event the inbound event carrying orderId, paymentId, transactionId, etc.
     */
    @KafkaListener(
            topics = "${kafka.topics.payment-completed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("[IDEMPOTENCY] Received PaymentCompletedEvent: orderId={}, paymentId={}, transactionId={}",
                event.getOrderId(), event.getPaymentId(), event.getTransactionId());

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            OrderResponse order = orderService.getOrderById(event.getOrderId());

            if (TERMINAL_STATUSES.contains(order.getStatus())) {
                log.warn("[IDEMPOTENCY] Skipping PaymentCompletedEvent for orderId={} — " +
                         "order is already in terminal status '{}'.",
                         event.getOrderId(), order.getStatus());
                sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "payment_completed", "skipped"));
                return;
            }

            orderService.updateOrderStatus(event.getOrderId(), OrderStatus.CONFIRMED);
            orderMetrics.recordSagaConfirmed();
            log.info("[SAGA] Order CONFIRMED: orderId={}, transactionId={}",
                    event.getOrderId(), event.getTransactionId());
            sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "payment_completed", "success"));

        } catch (Exception ex) {
            log.error("[SAGA] Error handling PaymentCompletedEvent for orderId={}: {}",
                    event.getOrderId(), ex.getMessage(), ex);
            sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "payment_completed", "error"));
        }
    }

    // ── 4. payment-failed → FAILED ────────────────────────────────────────────

    /**
     * Consumes a {@link PaymentFailedEvent}.
     *
     * <p>Payment could not be processed — the order is marked {@code FAILED}.
     * Stock release (compensation) should be triggered here in a future iteration.
     *
     * <p><strong>Idempotency:</strong> skips if the order is already in a
     * terminal state to avoid re-processing compensating logic.
     *
     * @param event the inbound event carrying orderId, amount, reason, etc.
     */
    @KafkaListener(
            topics = "${kafka.topics.payment-failed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.warn("[IDEMPOTENCY] Received PaymentFailedEvent: orderId={}, reason={}",
                event.getOrderId(), event.getReason());

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            OrderResponse order = orderService.getOrderById(event.getOrderId());

            if (TERMINAL_STATUSES.contains(order.getStatus())) {
                log.warn("[IDEMPOTENCY] Skipping PaymentFailedEvent for orderId={} — " +
                         "order is already in terminal status '{}'.",
                         event.getOrderId(), order.getStatus());
                sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "payment_failed", "skipped"));
                return;
            }

            orderService.updateOrderStatus(event.getOrderId(), OrderStatus.FAILED);
            orderMetrics.recordSagaFailed();
            log.warn("[SAGA] Order FAILED due to payment failure: orderId={}, reason={}",
                    event.getOrderId(), event.getReason());
            sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "payment_failed", "success"));

            // TODO: publish a stock-release / refund compensation event here
            //       once the compensation topics are defined.

        } catch (Exception ex) {
            log.error("[SAGA] Error handling PaymentFailedEvent for orderId={}: {}",
                    event.getOrderId(), ex.getMessage(), ex);
            sample.stop(OrderMetrics.sagaStepTimer(meterRegistry, "payment_failed", "error"));
        }
    }
}
