package com.order.processing.order.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    /** Payment method chosen by the user (e.g. CREDIT_CARD, DIGITAL_WALLET). */
    @Column(nullable = false)
    private String paymentMethod;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = OrderStatus.PENDING;
        }
    }

    public enum OrderStatus {
        /** Order received by the API; awaiting async saga processing. */
        PENDING,
        /** Legacy sync status — order synchronously validated and accepted. */
        PLACED,
        /** Stock reserved AND payment charged successfully. */
        CONFIRMED,
        SHIPPED,
        DELIVERED,
        /** Stock reservation failed — compensating transaction applied. */
        CANCELLED,
        /** Payment failed after stock was reserved. */
        FAILED
    }
}
