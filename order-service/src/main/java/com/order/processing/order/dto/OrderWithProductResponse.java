package com.order.processing.order.dto;

import com.order.processing.order.entity.Order.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Enriched response for {@code GET /orders/{id}}.
 *
 * <p>Combines the persisted order fields with live product details fetched
 * from product-service via a circuit-breaker-protected WebClient call.
 * When the circuit is open (product-service is down), the {@code product}
 * field is {@code null} and {@code productServiceAvailable} is {@code false},
 * allowing the caller to degrade gracefully.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderWithProductResponse {

    // ── Order fields ────────────────────────────────────────────────────────
    private Long orderId;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal totalPrice;
    private String paymentMethod;
    private OrderStatus status;
    private LocalDateTime createdAt;

    // ── Live product details from product-service ───────────────────────────
    /** Populated when product-service is healthy; {@code null} on fallback. */
    private ProductResponse product;

    /**
     * {@code true}  → product details fetched successfully from product-service.<br>
     * {@code false} → circuit is open or product-service is unreachable; order
     *                 data is still returned but {@code product} will be {@code null}.
     */
    private boolean productServiceAvailable;
}
