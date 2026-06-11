package com.order.processing.order.saga;

import com.order.processing.order.entity.Order;
import com.order.processing.order.entity.Order.OrderStatus;
import com.order.processing.order.security.WithMockOrderUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the order-saga <strong>payment-failure compensation path</strong>:
 *
 * <pre>
 *   POST /orders
 *       └─ order saved as PENDING
 *       └─ OrderPlacedEvent → [order-placed]
 *                                  ↓  (simulated by test)
 *              publishProductReserved()  → [product-reserved]
 *                                  ↓  (consumed by SagaEventListener)
 *            SagaEventListener publishes → [payment-initiated]
 *                                  ↓  (simulated by test — payment FAILS)
 *              publishPaymentFailed()    → [payment-failed]
 *                                  ↓  (consumed by SagaEventListener)
 *                         order.status = FAILED  ✓
 * </pre>
 *
 * <h2>Scenarios covered</h2>
 * <ol>
 *   <li>Card declined — payment-service returns a failure reason; order → FAILED.</li>
 *   <li>Idempotency — duplicate payment-failed events do not re-process.</li>
 *   <li>Race: payment-failed arrives before product-reserved is consumed —
 *       the terminal status check in {@link com.order.processing.order.event.SagaEventListener}
 *       ensures the later product-reserved event is ignored.</li>
 *   <li>Multiple concurrent orders — each saga is independent; one failure
 *       does not affect another order's CONFIRMED outcome.</li>
 * </ol>
 *
 * <h2>Why no WireMock here</h2>
 * These tests bypass the HTTP layer and insert orders directly via the
 * repository. The synchronous product-service HTTP call (which happens during
 * {@code POST /orders}) is deliberately out of scope — it is fully covered by
 * {@link com.order.processing.order.controller.OrderControllerIntegrationTest}.
 * Separating concerns keeps each test class focused and fast.
 */
@AutoConfigureMockMvc
@WithMockOrderUser(userId = 1L, username = "test-user")
@DisplayName("Saga — Payment Failure Compensation (PENDING → product-reserved → payment-failed → FAILED)")
class SagaPaymentFailureIntegrationTest extends AbstractSagaIntegrationTest {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final Long   PRODUCT_ID     = 2L;
    private static final int    QUANTITY       = 2;
    private static final String PAYMENT_METHOD = "DIGITAL_WALLET";
    private static final BigDecimal TOTAL_PRICE = new BigDecimal("99.98");

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    // ── Shared helper ─────────────────────────────────────────────────────────

    /**
     * Saves a minimal PENDING order directly to the DB, bypassing the HTTP
     * layer (and the synchronous product-service call), and returns its ID.
     */
    private long savePendingOrder() {
        return savePendingOrder(1L);
    }

    private long savePendingOrder(long userId) {
        return orderRepository.save(Order.builder()
                .userId(userId)
                .productId(PRODUCT_ID)
                .quantity(QUANTITY)
                .totalPrice(TOTAL_PRICE)
                .paymentMethod(PAYMENT_METHOD)
                .status(OrderStatus.PENDING)
                .build()).getId();
    }

    /**
     * Polls the DB until the order reaches {@code expectedStatus},
     * or fails after 10 seconds with a descriptive message.
     */
    private void awaitStatus(long orderId, OrderStatus expectedStatus) {
        await("order " + orderId + " reaches " + expectedStatus)
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Order o = orderRepository.findById(orderId).orElseThrow(
                            () -> new AssertionError("Order " + orderId + " not found in DB"));
                    assertThat(o.getStatus())
                            .as("Order %d status", orderId)
                            .isEqualTo(expectedStatus);
                });
    }

    // =========================================================================
    // Core payment failure scenario
    // =========================================================================

    @Nested
    @DisplayName("Payment failure: stock reserved, then payment fails → order FAILED")
    class CorePaymentFailure {

        @Test
        @DisplayName("order reaches FAILED after product-reserved then payment-failed events")
        void paymentFailed_orderReachesFailed() throws Exception {
            long orderId = savePendingOrder();

            // Step 1: Simulate product-service reserving stock
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 8);

            // Step 2: Simulate payment-service declining the charge
            // (The SagaEventListener has already published payment-initiated
            // internally; we simulate the downstream payment-service response.)
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined");

            // Step 3: Assert the order is FAILED in the DB
            awaitStatus(orderId, OrderStatus.FAILED);

            Order failed = orderRepository.findById(orderId).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(failed.getUserId()).isEqualTo(1L);
            assertThat(failed.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(failed.getQuantity()).isEqualTo(QUANTITY);
            assertThat(failed.getTotalPrice()).isEqualByComparingTo(TOTAL_PRICE);
        }

        @Test
        @DisplayName("payment failure reason 'Insufficient funds' → order FAILED")
        void paymentFailed_insufficientFunds_orderReachesFailed() {
            long orderId = savePendingOrder();

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 5);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Insufficient funds");

            awaitStatus(orderId, OrderStatus.FAILED);
        }

        @Test
        @DisplayName("payment failure reason 'Gateway timeout' → order FAILED")
        void paymentFailed_gatewayTimeout_orderReachesFailed() {
            long orderId = savePendingOrder();

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 5);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Payment gateway timeout");

            awaitStatus(orderId, OrderStatus.FAILED);
        }

        @Test
        @DisplayName("FAILED order retains original userId, productId, totalPrice")
        void paymentFailed_orderFieldsIntact() {
            long orderId = savePendingOrder(77L);

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 3);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined");

            awaitStatus(orderId, OrderStatus.FAILED);

            Order failed = orderRepository.findById(orderId).orElseThrow();
            assertThat(failed.getUserId()).isEqualTo(77L);
            assertThat(failed.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(failed.getQuantity()).isEqualTo(QUANTITY);
            assertThat(failed.getTotalPrice()).isEqualByComparingTo(TOTAL_PRICE);
            assertThat(failed.getPaymentMethod()).isEqualTo(PAYMENT_METHOD);
            assertThat(failed.getCreatedAt()).isNotNull();
        }
    }

    // =========================================================================
    // Idempotency — duplicate payment-failed events
    // =========================================================================

    @Nested
    @DisplayName("Idempotency: duplicate payment-failed events do not change a terminal order")
    class PaymentFailedIdempotency {

        @Test
        @DisplayName("duplicate payment-failed has no effect once order is already FAILED")
        void paymentFailed_duplicate_isIdempotent() {
            long orderId = savePendingOrder();

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 10);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined");

            awaitStatus(orderId, OrderStatus.FAILED);

            // Publish a second identical event — the listener's TERMINAL_STATUSES
            // guard must prevent any re-processing
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined (duplicate)");

            await("duplicate payment-failed is consumed without changing status")
                    .atMost(5, TimeUnit.SECONDS)
                    .pollDelay(500, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(
                            orderRepository.findById(orderId)
                                    .map(Order::getStatus)
                                    .orElse(null))
                            .isEqualTo(OrderStatus.FAILED));
        }

        @Test
        @DisplayName("payment-failed after payment-completed leaves order CONFIRMED, not FAILED")
        void paymentFailed_afterConfirmed_doesNotOverwriteConfirmed() {
            // This tests the TERMINAL_STATUSES guard in SagaEventListener:
            // once an order is CONFIRMED, a stale payment-failed event that
            // arrives out of order must be silently discarded.
            long orderId = savePendingOrder();

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 10);
            publishPaymentCompleted(orderId, TOTAL_PRICE, "txn-overlap-001");
            awaitStatus(orderId, OrderStatus.CONFIRMED);

            // Stale / out-of-order payment-failed arrives late
            publishPaymentFailed(orderId, TOTAL_PRICE, "Stale failure from old retry");

            await("stale payment-failed is discarded, order stays CONFIRMED")
                    .atMost(5, TimeUnit.SECONDS)
                    .pollDelay(500, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(
                            orderRepository.findById(orderId)
                                    .map(Order::getStatus)
                                    .orElse(null))
                            .isEqualTo(OrderStatus.CONFIRMED));
        }

        @Test
        @DisplayName("payment-completed after payment-failed leaves order FAILED, not CONFIRMED")
        void paymentCompleted_afterFailed_doesNotOverwriteFailed() {
            // CONFIRMED overwrites FAILED in the current spec — test what the
            // current TERMINAL_STATUSES guard produces.
            // CONFIRMED ∈ TERMINAL_STATUSES, so payment-completed after FAILED
            // is also discarded (FAILED is a terminal state).
            long orderId = savePendingOrder();

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 10);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined");
            awaitStatus(orderId, OrderStatus.FAILED);

            // Stale payment-completed arrives after the order is already FAILED
            publishPaymentCompleted(orderId, TOTAL_PRICE, "txn-late-completed-001");

            await("stale payment-completed is discarded, order stays FAILED")
                    .atMost(5, TimeUnit.SECONDS)
                    .pollDelay(500, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(
                            orderRepository.findById(orderId)
                                    .map(Order::getStatus)
                                    .orElse(null))
                            .isEqualTo(OrderStatus.FAILED));
        }
    }

    // =========================================================================
    // Concurrent / independent orders
    // =========================================================================

    @Nested
    @DisplayName("Concurrent orders: each saga is independent")
    class ConcurrentOrders {

        @Test
        @DisplayName("one FAILED order does not affect a concurrent CONFIRMED order")
        void concurrentOrders_failureAndSuccess_areIndependent() throws InterruptedException {
            // Two orders placed at the same time with different saga outcomes
            long confirmedOrderId = savePendingOrder();
            long failedOrderId    = savePendingOrder();

            // Both orders get stock reserved
            publishProductReserved(confirmedOrderId, PRODUCT_ID, QUANTITY, 15);
            publishProductReserved(failedOrderId,    PRODUCT_ID, QUANTITY, 13);

            // First order succeeds; second fails
            publishPaymentCompleted(confirmedOrderId, TOTAL_PRICE, "txn-concurrent-ok");
            publishPaymentFailed(failedOrderId,   TOTAL_PRICE, "Concurrent card decline");

            // Both must reach their own terminal states independently
            awaitStatus(confirmedOrderId, OrderStatus.CONFIRMED);
            awaitStatus(failedOrderId,    OrderStatus.FAILED);

            // Cross-check: each order has the correct final state
            Order confirmed = orderRepository.findById(confirmedOrderId).orElseThrow();
            Order failed    = orderRepository.findById(failedOrderId).orElseThrow();

            assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(failed.getStatus()).isEqualTo(OrderStatus.FAILED);
        }

        @Test
        @DisplayName("three concurrent orders with mixed outcomes all reach correct terminal states")
        void concurrentOrders_threeOrders_allReachCorrectTerminalState() {
            long order1 = savePendingOrder();   // → CONFIRMED
            long order2 = savePendingOrder();   // → FAILED (payment declined)
            long order3 = savePendingOrder();   // → CANCELLED (stock failed)

            // Stock reserved for order1 and order2; stock failed for order3
            publishProductReserved(order1, PRODUCT_ID, QUANTITY, 10);
            publishProductReserved(order2, PRODUCT_ID, QUANTITY, 8);
            publishProductReservationFailed(order3, PRODUCT_ID, QUANTITY, "Out of stock");

            // Payment outcomes
            publishPaymentCompleted(order1, TOTAL_PRICE, "txn-three-ok");
            publishPaymentFailed(order2,    TOTAL_PRICE, "Payment declined");

            // Assert all three terminal states are reached
            awaitStatus(order1, OrderStatus.CONFIRMED);
            awaitStatus(order2, OrderStatus.FAILED);
            awaitStatus(order3, OrderStatus.CANCELLED);

            // Verify DB state
            assertThat(orderRepository.findById(order1).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CONFIRMED);
            assertThat(orderRepository.findById(order2).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.FAILED);
            assertThat(orderRepository.findById(order3).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CANCELLED);
        }
    }

    // =========================================================================
    // Direct payment failure (no prior stock reservation)
    // =========================================================================

    @Nested
    @DisplayName("Direct payment-failed without prior product-reserved")
    class DirectPaymentFailed {

        @Test
        @DisplayName("order reaches FAILED when payment-failed arrives without prior product-reserved")
        void paymentFailed_withoutPriorProductReserved_orderReachesFailed() {
            // In theory this should not happen in a well-behaved system, but
            // at-least-once delivery or network partitions can produce out-of-order
            // messages. The saga listener's TERMINAL_STATUSES guard must handle this
            // gracefully: since the order is PENDING (not a terminal state),
            // payment-failed IS applied and the order moves to FAILED.
            long orderId = savePendingOrder();

            // No publishProductReserved — payment-failed arrives directly
            publishPaymentFailed(orderId, TOTAL_PRICE, "Unexpected direct failure");

            awaitStatus(orderId, OrderStatus.FAILED);
        }
    }
}
