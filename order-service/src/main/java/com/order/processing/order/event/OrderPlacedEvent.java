package com.order.processing.order.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Published to topic {@code order-placed} by the order-service immediately after
 * an order is persisted with status {@code PLACED}.
 *
 * <p>This is the first event in the order-processing saga. The product-service
 * consumes it to attempt stock reservation.
 *
 * <p>Message key: {@code orderId} (ensures all events for the same order land
 * on the same partition, preserving per-order ordering).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderPlacedEvent {

    /** The newly created order's primary key. */
    private Long orderId;

    /** The user who placed the order. */
    private Long userId;

    /** The product being ordered. */
    private Long productId;

    /** Number of units requested. */
    private Integer quantity;

    /** Pre-calculated unit price × quantity. */
    private BigDecimal totalPrice;

    /** Payment method chosen by the user (e.g. "CREDIT_CARD"). */
    private String paymentMethod;

    /** Timestamp when the order was created. */
    private LocalDateTime createdAt;
}
