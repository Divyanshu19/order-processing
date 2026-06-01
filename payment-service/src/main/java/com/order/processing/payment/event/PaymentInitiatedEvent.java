package com.order.processing.payment.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Consumed from topic {@code payment-initiated} by the payment-service.
 *
 * <p>This is the inbound event that tells the payment-service to process a charge
 * for the given order. Mirrors {@code order-service}'s {@code PaymentInitiatedEvent}
 * — kept as a separate class to avoid cross-service compile-time coupling.
 *
 * <p>Message key: {@code orderId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentInitiatedEvent {

    /** The order for which payment must be processed. */
    private Long orderId;

    /** The user to be charged. */
    private Long userId;

    /** The exact amount to charge. */
    private BigDecimal amount;

    /** Payment method (e.g. "CREDIT_CARD", "DIGITAL_WALLET"). */
    private String paymentMethod;

    /** Timestamp when this event was produced by the order-service. */
    private LocalDateTime initiatedAt;
}
