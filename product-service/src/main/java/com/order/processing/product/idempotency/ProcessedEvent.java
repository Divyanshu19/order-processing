package com.order.processing.product.idempotency;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Idempotency record for the product-service event consumers.
 *
 * <p>Before processing any inbound Kafka event, the listener checks whether a
 * row with the same {@code (eventType, orderId)} already exists. If it does,
 * the event is a duplicate (e.g. Kafka at-least-once re-delivery) and is
 * discarded without side-effects.
 *
 * <p>A row is inserted <em>inside the same DB transaction</em> that reduces
 * stock, so the check-and-insert is atomic — no race condition between two
 * concurrent deliveries of the same event.
 *
 * <p>The unique constraint on {@code (event_type, order_id)} also acts as a
 * hard guard: if a second thread attempts to insert the same key concurrently
 * it will receive a {@link org.springframework.dao.DataIntegrityViolationException}
 * which the listener translates into a duplicate-skip.
 */
@Entity
@Table(
    name = "processed_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_processed_event_type_order",
        columnNames = {"event_type", "order_id"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Logical event type key, e.g. {@code "ORDER_PLACED"}.
     * Kept short and stable — never use the full class name.
     */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /**
     * The order this event belongs to — part of the composite unique key.
     */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * Wall-clock time when the event was first processed.
     * Useful for diagnosing re-delivery lag in production.
     */
    @Column(name = "processed_at", nullable = false, updatable = false)
    private LocalDateTime processedAt;

    @PrePersist
    protected void onCreate() {
        processedAt = LocalDateTime.now();
    }
}
