package com.order.processing.order.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Published to topic {@code product-reservation-failed} by the product-service
 * when stock cannot be reserved for an order (e.g. insufficient stock, product
 * not found, or an unexpected error during deduction).
 *
 * <p>The order-service consumes this event to mark the order {@code CANCELLED}
 * — completing the compensating transaction for the saga.
 *
 * <p>Message key: {@code orderId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductReservationFailedEvent {

    /** The order for which reservation failed. */
    private Long orderId;

    /** The product that could not be reserved. */
    private Long productId;

    /** The quantity that was requested but could not be fulfilled. */
    private Integer requestedQuantity;

    /** Stock level at the time of the failure (0 if product not found). */
    private Integer availableStock;

    /** Human-readable reason for the failure. */
    private String reason;

    /** Timestamp when the failure was detected. */
    private LocalDateTime failedAt;
}
