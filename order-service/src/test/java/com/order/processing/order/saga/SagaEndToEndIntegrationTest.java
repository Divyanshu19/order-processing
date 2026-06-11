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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Comprehensive end-to-end integration tests for the <strong>complete order saga</strong>,
 * exercising every state transition and edge case with real Kafka + PostgreSQL.
 *
 * <h2>Architecture under test</h2>
 * <pre>
 *  DB (seed PENDING order)
 *       │
 *       ▼
 *  publishProductReserved()    →  [product-reserved]
 *                                       ↓
 *                          SagaEventListener.handleProductReserved()
 *                                       │
 *                          publishes → [payment-initiated]
 *                                       ↓
 *           publishPaymentCompleted()   │   publishPaymentFailed()
 *           → [payment-completed]       │   → [payment-failed]
 *                    ↓                  │            ↓
 *           order → CONFIRMED           │   order → FAILED
 *                                       │
 *  publishProductReservationFailed() ───┘
 *  → [product-reservation-failed]
 *             ↓
 *    order → CANCELLED
 * </pre>
 *
 * <h2>Covered scenarios</h2>
 * <ol>
 *   <li><b>Happy path</b> – full PENDING → (product-reserved) → (payment-completed) → CONFIRMED.</li>
 *   <li><b>Stock failure</b> – PENDING → (product-reservation-failed) → CANCELLED.</li>
 *   <li><b>Payment failure</b> – PENDING → (product-reserved) → (payment-failed) → FAILED.</li>
 *   <li><b>Idempotency — duplicate payment-completed</b> – CONFIRMED stays CONFIRMED.</li>
 *   <li><b>Idempotency — duplicate product-reservation-failed</b> – CANCELLED stays CANCELLED.</li>
 *   <li><b>Idempotency — out-of-order payment-failed after CONFIRMED</b> – CONFIRMED is not overwritten.</li>
 *   <li><b>Idempotency — out-of-order payment-completed after FAILED</b> – FAILED is not overwritten.</li>
 *   <li><b>Concurrent orders</b> – N independent orders all reach correct terminal states.</li>
 *   <li><b>Direct payment-failed</b> – no prior product-reserved; order still reaches FAILED.</li>
 *   <li><b>Rapid sequential events</b> – product-reserved immediately followed by payment-completed.</li>
 *   <li><b>Order field integrity</b> – userId, productId, totalPrice, paymentMethod preserved through saga.</li>
 * </ol>
 *
 * <h2>Why no HTTP layer</h2>
 * The synchronous product-service HTTP call (within {@code POST /orders}) is
 * covered in {@link OrderControllerIntegrationTest}. These tests seed orders
 * directly via the repository to keep the saga-state-machine boundary clean
 * and to avoid a WireMock dependency in the saga test base class.
 */
@AutoConfigureMockMvc
@WithMockOrderUser(userId = 1L, username = "saga-e2e-user")
@DisplayName("Saga E2E — Full saga state machine with real Kafka + PostgreSQL")
class SagaEndToEndIntegrationTest extends AbstractSagaIntegrationTest {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final Long   PRODUCT_ID     = 10L;
    private static final int    QUANTITY       = 5;
    private static final String PAYMENT_METHOD = "CREDIT_CARD";
    private static final BigDecimal TOTAL_PRICE = new BigDecimal("249.95");

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    /**
     * Seeds a PENDING order directly to the DB and returns its generated id.
     * Accepts an explicit userId to enable concurrent-order tests.
     */
    private long seedPendingOrder(long userId) {
        return orderRepository.save(Order.builder()
                .userId(userId)
                .productId(PRODUCT_ID)
                .quantity(QUANTITY)
                .totalPrice(TOTAL_PRICE)
                .paymentMethod(PAYMENT_METHOD)
                .status(OrderStatus.PENDING)
                .build()).getId();
    }

    private long seedPendingOrder() {
        return seedPendingOrder(1L);
    }

    /**
     * Polls the DB until the order reaches {@code expected}, or fails after
     * 10 s with a clear diagnostic.
     */
    private void awaitStatus(long orderId, OrderStatus expected) {
        await("order " + orderId + " reaches " + expected)
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Order o = orderRepository.findById(orderId)
                            .orElseThrow(() -> new AssertionError("Order " + orderId + " not found"));
                    assertThat(o.getStatus())
                            .as("Order %d status", orderId)
                            .isEqualTo(expected);
                });
    }

    // =========================================================================
    // 1. Full happy path — PENDING → CONFIRMED
    // =========================================================================

    @Nested
    @DisplayName("Happy path: PENDING → product-reserved → payment-completed → CONFIRMED")
    class HappyPath {

        @Test
        @DisplayName("order reaches CONFIRMED after product-reserved and payment-completed events")
        void happyPath_fullSaga_orderConfirmed() {
            long orderId = seedPendingOrder();

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 45);
            publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-txn-happy-001");

            awaitStatus(orderId, OrderStatus.CONFIRMED);

            Order confirmed = orderRepository.findById(orderId).orElseThrow();
            assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("CONFIRMED order retains all original field values")
        void happyPath_orderFieldsIntact_afterConfirmed() {
            long orderId = seedPendingOrder(42L);

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 40);
            publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-txn-fields-001");

            awaitStatus(orderId, OrderStatus.CONFIRMED);

            Order confirmed = orderRepository.findById(orderId).orElseThrow();
            assertThat(confirmed.getUserId()).isEqualTo(42L);
            assertThat(confirmed.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(confirmed.getQuantity()).isEqualTo(QUANTITY);
            assertThat(confirmed.getTotalPrice()).isEqualByComparingTo(TOTAL_PRICE);
            assertThat(confirmed.getPaymentMethod()).isEqualTo(PAYMENT_METHOD);
            assertThat(confirmed.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("status transitions: PENDING (stable) after product-reserved, then CONFIRMED")
        void happyPath_intermediateState_pendingAfterProductReserved() {
            long orderId = seedPendingOrder();

            // After product-reserved the order must stay PENDING
            // (SagaEventListener publishes payment-initiated but does NOT update status)
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 50);

            await("order stays PENDING after product-reserved")
                    .atMost(4, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() ->
                            assertThat(orderRepository.findById(orderId)
                                    .orElseThrow().getStatus())
                                    .isEqualTo(OrderStatus.PENDING));

            publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-txn-intermediate-001");
            awaitStatus(orderId, OrderStatus.CONFIRMED);
        }
    }

    // =========================================================================
    // 2. Stock reservation failure — PENDING → CANCELLED
    // =========================================================================

    @Nested
    @DisplayName("Stock reservation failure: PENDING → product-reservation-failed → CANCELLED")
    class StockFailure {

        @Test
        @DisplayName("order reaches CANCELLED after product-reservation-failed event")
        void stockFailed_orderCancelled() {
            long orderId = seedPendingOrder();

            publishProductReservationFailed(orderId, PRODUCT_ID, QUANTITY, "Out of stock");

            awaitStatus(orderId, OrderStatus.CANCELLED);

            Order cancelled = orderRepository.findById(orderId).orElseThrow();
            assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("CANCELLED order retains all original field values")
        void stockFailed_orderFieldsIntact_afterCancelled() {
            long orderId = seedPendingOrder(77L);

            publishProductReservationFailed(orderId, PRODUCT_ID, QUANTITY, "Product discontinued");

            awaitStatus(orderId, OrderStatus.CANCELLED);

            Order cancelled = orderRepository.findById(orderId).orElseThrow();
            assertThat(cancelled.getUserId()).isEqualTo(77L);
            assertThat(cancelled.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(cancelled.getQuantity()).isEqualTo(QUANTITY);
            assertThat(cancelled.getTotalPrice()).isEqualByComparingTo(TOTAL_PRICE);
        }

        @Test
        @DisplayName("stock failure reason 'Insufficient stock' produces CANCELLED order")
        void stockFailed_insufficientStock_orderCancelled() {
            long orderId = seedPendingOrder();
            publishProductReservationFailed(orderId, PRODUCT_ID, QUANTITY, "Insufficient stock");
            awaitStatus(orderId, OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("stock failure reason 'Product not found' produces CANCELLED order")
        void stockFailed_productNotFound_orderCancelled() {
            long orderId = seedPendingOrder();
            publishProductReservationFailed(orderId, PRODUCT_ID, QUANTITY, "Product not found");
            awaitStatus(orderId, OrderStatus.CANCELLED);
        }
    }

    // =========================================================================
    // 3. Payment failure — PENDING → (product-reserved) → FAILED
    // =========================================================================

    @Nested
    @DisplayName("Payment failure: PENDING → product-reserved → payment-failed → FAILED")
    class PaymentFailure {

        @Test
        @DisplayName("order reaches FAILED after product-reserved then payment-failed events")
        void paymentFailed_orderFailed() {
            long orderId = seedPendingOrder();

            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 30);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined");

            awaitStatus(orderId, OrderStatus.FAILED);

            Order failed = orderRepository.findById(orderId).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(OrderStatus.FAILED);
        }

        @Test
        @DisplayName("order reaches FAILED with reason 'Insufficient funds'")
        void paymentFailed_insufficientFunds_orderFailed() {
            long orderId = seedPendingOrder();
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 20);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Insufficient funds");
            awaitStatus(orderId, OrderStatus.FAILED);
        }

        @Test
        @DisplayName("order reaches FAILED with reason 'Payment gateway timeout'")
        void paymentFailed_gatewayTimeout_orderFailed() {
            long orderId = seedPendingOrder();
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 20);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Payment gateway timeout");
            awaitStatus(orderId, OrderStatus.FAILED);
        }

        @Test
        @DisplayName("FAILED order retains all original field values")
        void paymentFailed_orderFieldsIntact() {
            long orderId = seedPendingOrder(99L);
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 15);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined");
            awaitStatus(orderId, OrderStatus.FAILED);

            Order failed = orderRepository.findById(orderId).orElseThrow();
            assertThat(failed.getUserId()).isEqualTo(99L);
            assertThat(failed.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(failed.getQuantity()).isEqualTo(QUANTITY);
            assertThat(failed.getTotalPrice()).isEqualByComparingTo(TOTAL_PRICE);
            assertThat(failed.getPaymentMethod()).isEqualTo(PAYMENT_METHOD);
        }
    }

    // =========================================================================
    // 4 + 5. Idempotency — duplicate events do not corrupt terminal state
    // =========================================================================

    @Nested
    @DisplayName("Idempotency: duplicate events are silently discarded")
    class Idempotency {

        @Test
        @DisplayName("duplicate payment-completed after CONFIRMED keeps order CONFIRMED")
        void duplicate_paymentCompleted_afterConfirmed_staysConfirmed() {
            long orderId = seedPendingOrder();
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 10);
            publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-idem-001");
            awaitStatus(orderId, OrderStatus.CONFIRMED);

            // Duplicate delivery
            publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-idem-001-dup");

            await("duplicate payment-completed is discarded")
                    .atMost(5, TimeUnit.SECONDS)
                    .pollDelay(500, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() ->
                            assertThat(orderRepository.findById(orderId)
                                    .orElseThrow().getStatus())
                                    .isEqualTo(OrderStatus.CONFIRMED));
        }

        @Test
        @DisplayName("duplicate product-reservation-failed after CANCELLED keeps order CANCELLED")
        void duplicate_productReservationFailed_afterCancelled_staysCancelled() {
            long orderId = seedPendingOrder();
            publishProductReservationFailed(orderId, PRODUCT_ID, QUANTITY, "Out of stock");
            awaitStatus(orderId, OrderStatus.CANCELLED);

            publishProductReservationFailed(orderId, PRODUCT_ID, QUANTITY, "Duplicate event");

            await("duplicate reservation-failed is discarded")
                    .atMost(5, TimeUnit.SECONDS)
                    .pollDelay(500, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() ->
                            assertThat(orderRepository.findById(orderId)
                                    .orElseThrow().getStatus())
                                    .isEqualTo(OrderStatus.CANCELLED));
        }

        @Test
        @DisplayName("duplicate payment-failed after FAILED keeps order FAILED")
        void duplicate_paymentFailed_afterFailed_staysFailed() {
            long orderId = seedPendingOrder();
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 10);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined");
            awaitStatus(orderId, OrderStatus.FAILED);

            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined (duplicate)");

            await("duplicate payment-failed is discarded")
                    .atMost(5, TimeUnit.SECONDS)
                    .pollDelay(500, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() ->
                            assertThat(orderRepository.findById(orderId)
                                    .orElseThrow().getStatus())
                                    .isEqualTo(OrderStatus.FAILED));
        }

        @Test
        @DisplayName("6. out-of-order stale payment-failed after CONFIRMED does not overwrite CONFIRMED")
        void outOfOrder_paymentFailed_afterConfirmed_doesNotOverwrite() {
            long orderId = seedPendingOrder();
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 10);
            publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-overlap-001");
            awaitStatus(orderId, OrderStatus.CONFIRMED);

            // Stale failure arrives late (e.g. network partition)
            publishPaymentFailed(orderId, TOTAL_PRICE, "Stale failure");

            await("stale payment-failed is discarded — order stays CONFIRMED")
                    .atMost(5, TimeUnit.SECONDS)
                    .pollDelay(500, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() ->
                            assertThat(orderRepository.findById(orderId)
                                    .orElseThrow().getStatus())
                                    .isEqualTo(OrderStatus.CONFIRMED));
        }

        @Test
        @DisplayName("7. out-of-order stale payment-completed after FAILED does not overwrite FAILED")
        void outOfOrder_paymentCompleted_afterFailed_doesNotOverwrite() {
            long orderId = seedPendingOrder();
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 10);
            publishPaymentFailed(orderId, TOTAL_PRICE, "Card declined");
            awaitStatus(orderId, OrderStatus.FAILED);

            // Stale success arrives late
            publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-late-success-001");

            await("stale payment-completed is discarded — order stays FAILED")
                    .atMost(5, TimeUnit.SECONDS)
                    .pollDelay(500, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() ->
                            assertThat(orderRepository.findById(orderId)
                                    .orElseThrow().getStatus())
                                    .isEqualTo(OrderStatus.FAILED));
        }
    }

    // =========================================================================
    // 8. Concurrent orders — each saga is fully independent
    // =========================================================================

    @Nested
    @DisplayName("Concurrent orders: N independent sagas run without cross-contamination")
    class ConcurrentOrders {

        @Test
        @DisplayName("three concurrent orders with mixed outcomes all reach correct terminal states")
        void concurrentOrders_mixedOutcomes() {
            long order1 = seedPendingOrder();  // → CONFIRMED
            long order2 = seedPendingOrder();  // → FAILED (payment declined)
            long order3 = seedPendingOrder();  // → CANCELLED (stock failed)

            // Trigger all three in parallel
            publishProductReserved(order1, PRODUCT_ID, QUANTITY, 30);
            publishProductReserved(order2, PRODUCT_ID, QUANTITY, 25);
            publishProductReservationFailed(order3, PRODUCT_ID, QUANTITY, "Warehouse depleted");

            publishPaymentCompleted(order1, TOTAL_PRICE, "e2e-concurrent-ok");
            publishPaymentFailed(order2, TOTAL_PRICE, "Concurrent decline");

            // All three must reach their independent terminal states
            awaitStatus(order1, OrderStatus.CONFIRMED);
            awaitStatus(order2, OrderStatus.FAILED);
            awaitStatus(order3, OrderStatus.CANCELLED);

            // Cross-verify DB records
            assertThat(orderRepository.findById(order1).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(orderRepository.findById(order2).orElseThrow().getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(orderRepository.findById(order3).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("five concurrent CONFIRMED orders all succeed independently")
        void concurrentOrders_allConfirmed() throws InterruptedException {
            int count = 5;
            List<Long> orderIds = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                orderIds.add(seedPendingOrder((long) (i + 10)));
            }

            // Submit all events from multiple threads simultaneously
            ExecutorService exec = Executors.newFixedThreadPool(count);
            CountDownLatch latch = new CountDownLatch(count);
            for (int i = 0; i < count; i++) {
                final long orderId = orderIds.get(i);
                final int txnSuffix = i;
                exec.submit(() -> {
                    try {
                        publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 100 - txnSuffix);
                        publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-bulk-" + txnSuffix);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            // All must reach CONFIRMED
            for (long orderId : orderIds) {
                awaitStatus(orderId, OrderStatus.CONFIRMED);
            }

            // Verify DB
            for (long orderId : orderIds) {
                assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.CONFIRMED);
            }
        }
    }

    // =========================================================================
    // 9. Direct payment-failed without prior product-reserved
    // =========================================================================

    @Nested
    @DisplayName("Direct payment-failed: order reaches FAILED without prior product-reserved")
    class DirectPaymentFailed {

        @Test
        @DisplayName("payment-failed on a PENDING order (no product-reserved) → FAILED")
        void directPaymentFailed_pendingOrder_reachesFailed() {
            long orderId = seedPendingOrder();

            // No publishProductReserved — out-of-order / direct failure
            publishPaymentFailed(orderId, TOTAL_PRICE, "Unexpected direct payment failure");

            awaitStatus(orderId, OrderStatus.FAILED);
        }
    }

    // =========================================================================
    // 10. Rapid sequential events (stress: tight timing)
    // =========================================================================

    @Nested
    @DisplayName("Rapid sequential events: no race condition between product-reserved and payment-completed")
    class RapidEvents {

        @Test
        @DisplayName("product-reserved followed immediately by payment-completed → CONFIRMED")
        void rapidEvents_productReservedThenPaymentCompleted_orderConfirmed() {
            long orderId = seedPendingOrder();

            // Publish both events with no delay between them
            publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 20);
            publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-rapid-001");

            awaitStatus(orderId, OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("10 rapid orders in sequence all confirm without cross-contamination")
        void rapidEvents_tenSequentialOrders_allConfirmed() {
            int count = 10;
            List<Long> orderIds = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                long orderId = seedPendingOrder((long) (200 + i));
                orderIds.add(orderId);
                publishProductReserved(orderId, PRODUCT_ID, QUANTITY, 200 - i);
                publishPaymentCompleted(orderId, TOTAL_PRICE, "e2e-seq-" + i);
            }

            for (long orderId : orderIds) {
                awaitStatus(orderId, OrderStatus.CONFIRMED);
            }
        }
    }
}
