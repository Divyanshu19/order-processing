package com.order.processing.order.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Published to topic {@code payment-initiated} by the order-service after the
 * product-service confirms that stock has been successfully reserved
 * (i.e., after consuming a {@code product-reserved} event).
 *
 * <p>The payment-service consumes this event to process the charge.
 *
 * <p>Message key: {@code orderId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentInitiatedEvent {

    /** The order for which payment is being requested. */
    private Long orderId;

    /** The user to be charged. */
    private Long userId;

    /** The amount to charge (totalPrice from the order). */
    private BigDecimal amount;

    /** Payment method chosen at order time (e.g. "CREDIT_CARD"). */
    private String paymentMethod;

    /** Timestamp when this event was produced. */
    private LocalDateTime initiatedAt;
}
