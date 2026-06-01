package com.order.processing.payment.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Published to topic {@code payment-failed} by the payment-service when a charge
 * cannot be completed (e.g. card declined, gateway timeout, insufficient funds).
 *
 * <p>The order-service consumes this event to mark the order {@code FAILED}
 * — completing the compensating transaction for the payment step.
 *
 * <p>Message key: {@code orderId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentFailedEvent {

    /** The order for which payment failed. */
    private Long orderId;

    /** The amount that could not be charged. */
    private BigDecimal amount;

    /** Human-readable reason for the payment failure. */
    private String reason;

    /** Timestamp when the failure was detected. */
    private LocalDateTime failedAt;
}
