package com.order.processing.payment.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Published to topic {@code payment-completed} by the payment-service after a
 * charge is successfully processed.
 *
 * <p>The order-service consumes this event to mark the order {@code CONFIRMED}
 * — the final successful state in the saga.
 *
 * <p>Message key: {@code orderId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentCompletedEvent {

    /** The order that has been paid for. */
    private Long orderId;

    /** The payment record created in the payment-service DB. */
    private Long paymentId;

    /** The unique transaction identifier from the payment processor. */
    private String transactionId;

    /** The amount that was successfully charged. */
    private BigDecimal amount;

    /** Timestamp when the payment was confirmed. */
    private LocalDateTime completedAt;
}
