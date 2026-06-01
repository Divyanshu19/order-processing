package com.order.processing.payment.event;

import com.order.processing.payment.dto.PaymentRequest;
import com.order.processing.payment.dto.PaymentResponse;
import com.order.processing.payment.entity.Payment.PaymentMethod;
import com.order.processing.payment.repository.PaymentRepository;
import com.order.processing.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * Consumes {@code PaymentInitiatedEvent} messages from the {@code payment-initiated}
 * topic and attempts to process the payment.
 *
 * <p>This is the fourth step in the order-processing saga:
 *
 * <pre>
 *  [payment-initiated]  →  processPayment()
 *                               │
 *                     ┌─────────┴──────────┐
 *                     ▼                    ▼
 *             payment succeeds        payment fails
 *           [payment-completed]     [payment-failed]
 *                   │                      │
 *             order-service           order-service
 *           marks CONFIRMED           marks FAILED
 * </pre>
 *
 * <p>Payment outcome is simulated with a configurable random probability
 * (70 % success / 30 % failure) so the full saga can be exercised end-to-end
 * without a real payment gateway. Replace {@link #simulatePaymentOutcome} with
 * a live gateway call when integrating a real provider.
 *
 * <h3>Idempotency</h3>
 * <p>Kafka's at-least-once delivery means this event can arrive more than once.
 * Processing it twice would charge the customer a second time. Before doing
 * anything, the handler checks whether a {@code Payment} row already exists for
 * the incoming {@code orderId}. If one is found, the event is a duplicate and
 * the handler returns immediately without re-charging or re-publishing.
 *
 * <p>Consumer group: {@code payment-service-group} (set in {@code application.yml}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentInitiatedEventListener {

    private static final double SUCCESS_PROBABILITY = 0.70;
    private static final Random RANDOM = new Random();

    private final PaymentService paymentService;
    private final PaymentEventPublisher paymentEventPublisher;

    /**
     * Used for the idempotency check only — we inspect whether a payment
     * record already exists for the given {@code orderId} before processing.
     */
    private final PaymentRepository paymentRepository;

    /**
     * Handles an incoming {@link PaymentInitiatedEvent}.
     *
     * <p>Processing steps:
     * <ol>
     *   <li><strong>Idempotency check</strong> — skip if a payment for this
     *       orderId already exists in the DB.</li>
     *   <li>Simulate payment outcome (70 % success, 30 % failure).</li>
     *   <li>On success → persist payment record via {@link PaymentService#processPayment},
     *       then publish {@link PaymentCompletedEvent}.</li>
     *   <li>On simulated failure → publish {@link PaymentFailedEvent} with reason
     *       "Payment declined (simulated)".</li>
     *   <li>On unexpected exception → publish {@link PaymentFailedEvent} so the
     *       saga is never left dangling.</li>
     * </ol>
     *
     * @param event the deserialized event from the {@code payment-initiated} topic
     */
    @KafkaListener(
            topics = "${kafka.topics.payment-initiated}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handlePaymentInitiated(PaymentInitiatedEvent event) {
        log.info("[IDEMPOTENCY] Received PaymentInitiatedEvent: orderId={}, userId={}, amount={}, method={}",
                event.getOrderId(), event.getUserId(), event.getAmount(), event.getPaymentMethod());

        // ── Idempotency guard: skip if payment already processed ──────────────
        boolean alreadyProcessed = !paymentRepository.findByOrderId(event.getOrderId()).isEmpty();
        if (alreadyProcessed) {
            log.warn("[IDEMPOTENCY] Duplicate PaymentInitiatedEvent detected — skipping. " +
                     "A payment for orderId={} already exists in the DB.", event.getOrderId());
            return;
        }

        try {
            // ── Step 1: Simulate payment gateway outcome ──────────────────────
            if (!simulatePaymentOutcome()) {
                log.warn("[SAGA] Payment declined (simulated) for orderId={}", event.getOrderId());
                publishFailure(event, "Payment declined (simulated)");
                return;
            }

            // ── Step 2: Resolve PaymentMethod enum — default to CREDIT_CARD ──
            PaymentMethod paymentMethod = resolvePaymentMethod(event.getPaymentMethod());

            // ── Step 3: Persist payment record ────────────────────────────────
            PaymentRequest paymentRequest = PaymentRequest.builder()
                    .orderId(event.getOrderId())
                    .userId(event.getUserId())
                    .amount(event.getAmount())
                    .paymentMethod(paymentMethod)
                    .build();

            PaymentResponse response = paymentService.processPayment(paymentRequest);

            log.info("[SAGA] Payment succeeded for orderId={}, paymentId={}, transactionId={}",
                    event.getOrderId(), response.getPaymentId(), response.getTransactionId());

            // ── Step 4: Publish success event ─────────────────────────────────
            PaymentCompletedEvent completedEvent = PaymentCompletedEvent.builder()
                    .orderId(event.getOrderId())
                    .paymentId(response.getPaymentId())
                    .transactionId(response.getTransactionId())
                    .amount(response.getAmount())
                    .completedAt(LocalDateTime.now())
                    .build();

            paymentEventPublisher.publishPaymentCompleted(completedEvent);

        } catch (Exception ex) {
            // ── Step 5: Unexpected error — saga must still advance ────────────
            log.error("[SAGA] Unexpected error processing payment for orderId={}: {}",
                    event.getOrderId(), ex.getMessage(), ex);
            publishFailure(event, "Unexpected error: " + ex.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} with {@link #SUCCESS_PROBABILITY} probability.
     * Simulates a real payment gateway that sometimes declines.
     */
    private boolean simulatePaymentOutcome() {
        return RANDOM.nextDouble() < SUCCESS_PROBABILITY;
    }

    /**
     * Safely maps a payment method string to a {@link PaymentMethod} enum value.
     * Defaults to {@code CREDIT_CARD} if the value is blank or unrecognised.
     */
    private PaymentMethod resolvePaymentMethod(String method) {
        if (method == null || method.isBlank()) {
            return PaymentMethod.CREDIT_CARD;
        }
        try {
            return PaymentMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Unrecognised paymentMethod='{}', defaulting to CREDIT_CARD", method);
            return PaymentMethod.CREDIT_CARD;
        }
    }

    /**
     * Builds and publishes a {@link PaymentFailedEvent} so the order-service
     * always receives a terminal event and can mark the order {@code FAILED}.
     */
    private void publishFailure(PaymentInitiatedEvent event, String reason) {
        PaymentFailedEvent failedEvent = PaymentFailedEvent.builder()
                .orderId(event.getOrderId())
                .amount(event.getAmount())
                .reason(reason)
                .failedAt(LocalDateTime.now())
                .build();

        paymentEventPublisher.publishPaymentFailed(failedEvent);
    }
}
