package com.order.processing.order.repository;

import com.order.processing.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);

    List<Order> findByProductId(Long productId);

    List<Order> findByStatus(Order.OrderStatus status);

    /**
     * Returns {@code true} if an order with the given {@code id} exists
     * <em>and</em> its current status matches {@code status}.
     *
     * <p>Used by idempotency checks in {@code SagaEventListener} to decide
     * whether a saga transition should be applied or skipped.
     *
     * @param id     the order primary key
     * @param status the status to check against
     * @return {@code true} when the order is in the expected status
     */
    boolean existsByIdAndStatus(Long id, Order.OrderStatus status);
}
