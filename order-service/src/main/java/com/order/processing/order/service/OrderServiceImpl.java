package com.order.processing.order.service;

import com.order.processing.order.client.ProductServiceClient;
import com.order.processing.order.dto.OrderRequest;
import com.order.processing.order.dto.OrderResponse;
import com.order.processing.order.dto.OrderWithProductResponse;
import com.order.processing.order.dto.ProductResponse;
import com.order.processing.order.entity.Order;
import com.order.processing.order.entity.Order.OrderStatus;
import com.order.processing.order.event.OrderEventPublisher;
import com.order.processing.order.event.OrderPlacedEvent;
import com.order.processing.order.exception.InsufficientStockException;
import com.order.processing.order.exception.OrderNotFoundException;
import com.order.processing.order.repository.OrderRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * Accepts an order request, validates it synchronously, persists it with
     * status {@code PENDING}, and fires an {@code OrderPlacedEvent} to Kafka.
     * Returns immediately — the saga continues asynchronously:
     *
     * <pre>
     *  POST /orders
     *       │
     *       ├─ 1. GET product-service  →  validate existence + get unit price
     *       ├─ 2. Pre-flight stock check  →  fail fast (409) if qty > stock
     *       ├─ 3. totalPrice = price × quantity
     *       ├─ 4. INSERT orders (status = PENDING)  →  committed immediately
     *       ├─ 5. publish → [order-placed]  →  product-service picks up async
     *       └─ 6. return 201  { ..., status: "PENDING" }
     *
     *  Async saga (driven by KafkaX):
     *       [order-placed]  →  product-service reserves stock
     *                       →  [product-reserved]      →  order-service publishes [payment-initiated]
     *                       →  [product-res-failed]    →  order-service marks CANCELLED
     *       [payment-initiated] →  payment-service charges
     *                           →  [payment-completed] →  order-service marks CONFIRMED
     *                           →  [payment-failed]    →  order-service marks FAILED
     * </pre>
     *
     * <p>The product fetch is kept synchronous so the caller gets immediate
     * feedback on invalid productIds or obvious stock shortfalls, avoiding
     * a round-trip through the entire async saga for trivially invalid requests.
     */
    @Override
    public OrderResponse createOrder(OrderRequest request) {
        log.info("Received order request: userId={}, productId={}, quantity={}",
                request.getUserId(), request.getProductId(), request.getQuantity());

        // ── Step 1: Fetch product — validate existence and get unit price ──────
        ProductResponse product = productServiceClient.getProductById(request.getProductId());
        log.info("Product validated: name='{}', price={}, stock={}",
                product.getName(), product.getPrice(), product.getStockQuantity());

        // ── Step 2: Pre-flight stock check (fail fast before any DB write) ─────
        if (product.getStockQuantity() < request.getQuantity()) {
            throw new InsufficientStockException(
                    request.getProductId(),
                    product.getStockQuantity(),
                    request.getQuantity());
        }

        // ── Step 3: Calculate total price ─────────────────────────────────────
        BigDecimal totalPrice = product.getPrice()
                .multiply(BigDecimal.valueOf(request.getQuantity()));
        log.info("Calculated totalPrice={} (price={} × qty={})",
                totalPrice, product.getPrice(), request.getQuantity());

        // ── Step 4: Persist order with status PENDING ─────────────────────────
        Order order = Order.builder()
                .userId(request.getUserId())
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .totalPrice(totalPrice)
                .paymentMethod(request.getPaymentMethod())
                .status(OrderStatus.PENDING)
                .build();
        order = orderRepository.save(order);
        log.info("Order saved: id={}, status={}", order.getId(), order.getStatus());

        // ── Step 5: Publish OrderPlacedEvent → triggers async saga ────────────
        OrderPlacedEvent event = OrderPlacedEvent.builder()
                .orderId(order.getId())
                .userId(order.getUserId())
                .productId(order.getProductId())
                .quantity(order.getQuantity())
                .totalPrice(order.getTotalPrice())
                .paymentMethod(request.getPaymentMethod())
                .createdAt(order.getCreatedAt())
                .build();
        orderEventPublisher.publishOrderPlaced(event);

        // ── Step 6: Return immediately — saga continues asynchronously ─────────
        return toResponse(order);
    }

    @Override
    public OrderResponse getOrderById(Long orderId) {
        log.debug("Fetching order: orderId={}", orderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return toResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatus status) {
        log.info("Updating order status: orderId={}, newStatus={}", orderId, status);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previous = order.getStatus();
        order.setStatus(status);
        order = orderRepository.save(order);

        log.info("Order status updated: orderId={}, {} → {}", orderId, previous, status);
        return toResponse(order);
    }

    @Override
    public OrderWithProductResponse getOrderWithProduct(Long orderId) {
        log.info("Fetching enriched order with product details: orderId={}", orderId);

        // ── Step 1: Load order from DB (throws OrderNotFoundException if absent) ─
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // ── Step 2: Fetch product via circuit-breaker-protected WebClient call ──
        // Returns null when the circuit is OPEN or product-service is unreachable.
        ProductResponse product =
                productServiceClient.getProductByIdWithCircuitBreaker(order.getProductId());

        boolean productAvailable = product != null;
        if (productAvailable) {
            log.info("Product details fetched successfully for productId={}", order.getProductId());
        } else {
            log.warn("Product-service unavailable or circuit OPEN for productId={}; " +
                    "returning order with degraded product info", order.getProductId());
        }

        // ── Step 3: Build enriched response ────────────────────────────────────
        return OrderWithProductResponse.builder()
                .orderId(order.getId())
                .userId(order.getUserId())
                .productId(order.getProductId())
                .quantity(order.getQuantity())
                .totalPrice(order.getTotalPrice())
                .paymentMethod(order.getPaymentMethod())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .product(product)
                .productServiceAvailable(productAvailable)
                .build();
    }

    private OrderResponse toResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .productId(order.getProductId())
                .quantity(order.getQuantity())
                .totalPrice(order.getTotalPrice())
                .paymentMethod(order.getPaymentMethod())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .build();
    }
}
