package com.order.processing.order.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Published to topic {@code product-reserved} by the product-service after stock
 * has been successfully reduced in response to an {@code order-placed} event.
 *
 * <p>The order-service consumes this event to advance the saga by publishing
 * a {@code payment-initiated} event.
 *
 * <p>Message key: {@code orderId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductReservedEvent {

    /** The order this reservation belongs to. */
    private Long orderId;

    /** The product whose stock was reduced. */
    private Long productId;

    /** Number of units successfully reserved. */
    private Integer quantity;

    /** Stock level remaining after reservation. */
    private Integer remainingStock;

    /** Timestamp when the reservation was confirmed. */
    private LocalDateTime reservedAt;
}
