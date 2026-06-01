package com.order.processing.order.service;

import com.order.processing.order.dto.OrderRequest;
import com.order.processing.order.dto.OrderResponse;
import com.order.processing.order.entity.Order.OrderStatus;

public interface OrderService {

    /**
     * Transitions an existing order to the given {@code status}.
     *
     * <p>Called by the saga event listener when downstream events arrive
     * (e.g., {@code product-reserved}, {@code payment-completed}).
     *
     * @param orderId the PK of the order to update
     * @param status  the target {@link OrderStatus}
     * @return the updated order
     * @throws com.order.processing.order.exception.OrderNotFoundException if no
     *         order exists for the given {@code orderId}
     */
    OrderResponse updateOrderStatus(Long orderId, OrderStatus status);

    /**
     * Returns the order for the given {@code orderId}.
     *
     * @param orderId the PK of the order
     * @return the order details
     * @throws com.order.processing.order.exception.OrderNotFoundException if absent
     */
    OrderResponse getOrderById(Long orderId);

    /**
     * Validates the order request synchronously, persists the order with status
     * {@code PENDING}, and publishes an {@link com.order.processing.order.event.OrderPlacedEvent}
     * to the {@code order-placed} Kafka topic.
     *
     * <p>Returns immediately with {@code PENDING} status. The full saga
     * (stock reservation → payment → confirmation) is driven asynchronously
     * by subsequent Kafka events.
     *
     * @param request the incoming order request (userId, productId, quantity, paymentMethod)
     * @return the persisted order with status {@code PENDING}
     */
    OrderResponse createOrder(OrderRequest request);
}
