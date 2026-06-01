package com.order.processing.product.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link ProcessedEvent} idempotency records.
 *
 * <p>The primary query used by consumers is {@link #existsByEventTypeAndOrderId},
 * which maps directly to the unique index on {@code (event_type, order_id)} and
 * therefore executes as a single indexed lookup — O(log n).
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    /**
     * Returns {@code true} if an event of the given type has already been
     * successfully processed for the specified order.
     *
     * @param eventType a short stable string, e.g. {@code "ORDER_PLACED"}
     * @param orderId   the order primary key
     * @return {@code true} if a matching row exists; {@code false} otherwise
     */
    boolean existsByEventTypeAndOrderId(String eventType, Long orderId);
}
