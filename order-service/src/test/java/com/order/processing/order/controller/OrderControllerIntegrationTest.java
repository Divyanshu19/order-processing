package com.order.processing.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.order.processing.order.AbstractIntegrationTest;
import com.order.processing.order.dto.OrderRequest;
import com.order.processing.order.entity.Order;
import com.order.processing.order.entity.Order.OrderStatus;
import com.order.processing.order.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for {@link OrderController}.
 *
 * <p><strong>Strategy:</strong>
 * <ul>
 *   <li>Full Spring ApplicationContext loaded (JPA, validation, exception handlers).</li>
 *   <li>H2 in-memory DB — no PostgreSQL required.</li>
 *   <li>{@link MockRestServiceServer} intercepts {@link RestTemplate} calls to
 *       product-service and payment-service at the HTTP layer, exercising the real
 *       client code (URL building, JSON (de)serialisation, error mapping).</li>
 *   <li>{@link MockMvc} drives the HTTP layer of the order-service itself.</li>
 *   <li>Each test asserts both the HTTP response AND the DB state via
 *       {@link OrderRepository}.</li>
 * </ul>
 */
@AutoConfigureMockMvc
@DisplayName("OrderController — End-to-End Integration Tests")
class OrderControllerIntegrationTest extends AbstractIntegrationTest {

    // ── Injected beans ────────────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RestTemplate restTemplate;

    // ── External service base URLs (from integration-test profile) ────────────

    @Value("${product-service.base-url}")
    private String productBaseUrl;

    @Value("${payment-service.base-url}")
    private String paymentBaseUrl;

    // ── MockRestServiceServer — intercepts RestTemplate calls ─────────────────

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        mockServer.reset();
    }

    // =========================================================================
    // Shared JSON stubs
    // =========================================================================

    /** product-service GET /products/1 — product in stock */
    private static final String PRODUCT_IN_STOCK_JSON = """
            {
              "id": 1,
              "sku": "SKU-WIDGET",
              "name": "Widget Pro",
              "price": 49.99,
              "stockQuantity": 20
            }
            """;

    /** product-service GET /products/1 — product with only 2 items left */
    private static final String PRODUCT_LOW_STOCK_JSON = """
            {
              "id": 1,
              "sku": "SKU-WIDGET",
              "name": "Widget Pro",
              "price": 49.99,
              "stockQuantity": 2
            }
            """;

    /** product-service PUT /products/1/stock — successful stock reduction */
    private static final String STOCK_REDUCED_JSON = """
            {
              "id": 1,
              "sku": "SKU-WIDGET",
              "name": "Widget Pro",
              "price": 49.99,
              "stockQuantity": 17
            }
            """;

    /** payment-service POST /payments — SUCCESS */
    private static final String PAYMENT_SUCCESS_JSON = """
            {
              "paymentId": 101,
              "orderId": 1,
              "userId": 42,
              "amount": 149.97,
              "paymentMethod": "CREDIT_CARD",
              "status": "SUCCESS",
              "transactionId": "txn-abc-123",
              "message": "Payment processed successfully"
            }
            """;

    /** payment-service POST /payments — FAILED */
    private static final String PAYMENT_FAILED_JSON = """
            {
              "paymentId": 102,
              "orderId": 1,
              "userId": 42,
              "amount": 149.97,
              "paymentMethod": "CREDIT_CARD",
              "status": "FAILED",
              "transactionId": "txn-fail-999",
              "message": "Card declined"
            }
            """;

    // =========================================================================
    // Helpers
    // =========================================================================

    private OrderRequest validRequest() {
        return OrderRequest.builder()
                .userId(42L)
                .productId(1L)
                .quantity(3)
                .paymentMethod("CREDIT_CARD")
                .build();
    }

    /**
     * Registers the three happy-path mock expectations in order:
     *  1. GET /products/1      → PRODUCT_IN_STOCK_JSON
     *  2. PUT /products/1/stock → STOCK_REDUCED_JSON
     *  3. POST /payments        → PAYMENT_SUCCESS_JSON
     */
    private void stubHappyPath() {
        mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PRODUCT_IN_STOCK_JSON, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(productBaseUrl + "/products/1/stock"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(STOCK_REDUCED_JSON, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(paymentBaseUrl + "/payments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(PAYMENT_SUCCESS_JSON, MediaType.APPLICATION_JSON));
    }

    // =========================================================================
    // Happy path — place order → stock decreases → payment charged → CONFIRMED
    // =========================================================================

    @Nested
    @DisplayName("Happy path — full order lifecycle")
    class HappyPath {

        @Test
        @DisplayName("returns 201 with CONFIRMED order when product exists, stock sufficient, payment succeeds")
        void placeOrder_allServicesSucceed_returns201Confirmed() throws Exception {
            stubHappyPath();

            String responseBody = mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.userId").value(42))
                    .andExpect(jsonPath("$.productId").value(1))
                    .andExpect(jsonPath("$.quantity").value(3))
                    .andExpect(jsonPath("$.totalPrice").value(149.97))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.createdAt", notNullValue()))
                    .andReturn().getResponse().getContentAsString();

            // ── Assert DB state ────────────────────────────────────────────────
            Long orderId = objectMapper.readTree(responseBody).get("id").asLong();
            Order persisted = orderRepository.findById(orderId).orElseThrow();

            assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(persisted.getUserId()).isEqualTo(42L);
            assertThat(persisted.getProductId()).isEqualTo(1L);
            assertThat(persisted.getQuantity()).isEqualTo(3);
            assertThat(persisted.getTotalPrice()).isEqualByComparingTo("149.97");
            assertThat(persisted.getCreatedAt()).isNotNull();

            // ── All expected HTTP calls were made ──────────────────────────────
            mockServer.verify();
        }

        @Test
        @DisplayName("totalPrice = product.price × quantity is correctly calculated")
        void placeOrder_totalPriceCalculatedCorrectly() throws Exception {
            // price=49.99 × quantity=3 → 149.97
            stubHappyPath();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.totalPrice").value(149.97));
        }

        @Test
        @DisplayName("product-service GET and PUT /stock are both called exactly once")
        void placeOrder_productServiceCalledForFetchAndStockReduction() throws Exception {
            stubHappyPath();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated());

            // mockServer.verify() throws if any expectation was not satisfied
            mockServer.verify();
        }

        @Test
        @DisplayName("payment-service POST /payments is called with correct orderId and amount")
        void placeOrder_paymentServiceCalledWithCorrectPayload() throws Exception {
            stubHappyPath();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));

            mockServer.verify();
        }

        @Test
        @DisplayName("order is durably persisted in the database with status CONFIRMED")
        void placeOrder_orderPersistedInDatabase() throws Exception {
            stubHappyPath();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated());

            assertThat(orderRepository.count()).isEqualTo(1);
            Order saved = orderRepository.findAll().get(0);
            assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(saved.getTotalPrice()).isEqualByComparingTo(new BigDecimal("149.97"));
        }
    }

    // =========================================================================
    // Pre-flight stock check — fails before any DB write
    // =========================================================================

    @Nested
    @DisplayName("Pre-flight stock check — quantity exceeds available stock")
    class PreflightStockCheck {

        @Test
        @DisplayName("returns 409 and no order is persisted when requested quantity > stockQuantity")
        void placeOrder_quantityExceedsStock_returns409_noOrderPersisted() throws Exception {
            // Stock is 2, but requesting 5
            mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(PRODUCT_LOW_STOCK_JSON, MediaType.APPLICATION_JSON));

            OrderRequest request = OrderRequest.builder()
                    .userId(42L).productId(1L).quantity(5).paymentMethod("CREDIT_CARD")
                    .build();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(containsString("Insufficient stock")));

            // No order record must have been written
            assertThat(orderRepository.count()).isZero();
        }

        @Test
        @DisplayName("stock PUT and payment POST are never called when pre-flight check fails")
        void placeOrder_preflightFails_stockAndPaymentNotCalled() throws Exception {
            // Only the GET is expected — PUT and POST must NOT be called
            mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(PRODUCT_LOW_STOCK_JSON, MediaType.APPLICATION_JSON));

            OrderRequest request = OrderRequest.builder()
                    .userId(42L).productId(1L).quantity(5).paymentMethod("CREDIT_CARD")
                    .build();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());

            // verify() confirms no unexpected calls were made
            mockServer.verify();
        }
    }

    // =========================================================================
    // Stock reservation failure — product-service returns 409 on PUT /stock
    // =========================================================================

    @Nested
    @DisplayName("Stock reservation failure — product-service 409 on PUT /stock")
    class StockReservationFailure {

        @Test
        @DisplayName("returns 409 when stock reservation fails after order is already PLACED")
        void placeOrder_stockReservationFails_returns409() throws Exception {
            mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(PRODUCT_IN_STOCK_JSON, MediaType.APPLICATION_JSON));

            // product-service returns 409 on the stock reduction call
            mockServer.expect(requestTo(productBaseUrl + "/products/1/stock"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withStatus(HttpStatus.CONFLICT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"message\":\"Insufficient stock\"}"));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("order status is set to CANCELLED in the DB when stock reservation fails")
        void placeOrder_stockReservationFails_orderMarkedCancelled() throws Exception {
            mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(PRODUCT_IN_STOCK_JSON, MediaType.APPLICATION_JSON));

            mockServer.expect(requestTo(productBaseUrl + "/products/1/stock"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withStatus(HttpStatus.CONFLICT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"message\":\"Insufficient stock\"}"));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict());

            // The order was created (PLACED) then immediately marked CANCELLED
            assertThat(orderRepository.count()).isEqualTo(1);
            Order order = orderRepository.findAll().get(0);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("payment-service is NOT called when stock reservation fails")
        void placeOrder_stockReservationFails_paymentNotCalled() throws Exception {
            mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(PRODUCT_IN_STOCK_JSON, MediaType.APPLICATION_JSON));

            mockServer.expect(requestTo(productBaseUrl + "/products/1/stock"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withStatus(HttpStatus.CONFLICT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"message\":\"Insufficient stock\"}"));

            // No payment expectation registered → verify() would fail if it was called
            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict());

            mockServer.verify();
        }
    }

    // =========================================================================
    // Payment failure — payment-service returns non-SUCCESS
    // =========================================================================

    @Nested
    @DisplayName("Payment failure — payment-service returns FAILED or error")
    class PaymentFailure {

        @Test
        @DisplayName("returns 502 when payment-service returns FAILED status")
        void placeOrder_paymentFailed_returns502() throws Exception {
            mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(PRODUCT_IN_STOCK_JSON, MediaType.APPLICATION_JSON));

            mockServer.expect(requestTo(productBaseUrl + "/products/1/stock"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withSuccess(STOCK_REDUCED_JSON, MediaType.APPLICATION_JSON));

            // payment-service returns 200 but with status=FAILED in the body
            mockServer.expect(requestTo(paymentBaseUrl + "/payments"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(PAYMENT_FAILED_JSON, MediaType.APPLICATION_JSON));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.status").value(502))
                    .andExpect(jsonPath("$.message").value(containsString("Payment was not successful")));
        }

        @Test
        @DisplayName("order status is set to FAILED in the DB when payment fails")
        void placeOrder_paymentFailed_orderMarkedFailed() throws Exception {
            mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(PRODUCT_IN_STOCK_JSON, MediaType.APPLICATION_JSON));

            mockServer.expect(requestTo(productBaseUrl + "/products/1/stock"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withSuccess(STOCK_REDUCED_JSON, MediaType.APPLICATION_JSON));

            mockServer.expect(requestTo(paymentBaseUrl + "/payments"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(PAYMENT_FAILED_JSON, MediaType.APPLICATION_JSON));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isBadGateway());

            assertThat(orderRepository.count()).isEqualTo(1);
            Order order = orderRepository.findAll().get(0);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        }

        @Test
        @DisplayName("returns 502 when payment-service is unreachable")
        void placeOrder_paymentServiceDown_returns502() throws Exception {
            mockServer.expect(requestTo(productBaseUrl + "/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(PRODUCT_IN_STOCK_JSON, MediaType.APPLICATION_JSON));

            mockServer.expect(requestTo(productBaseUrl + "/products/1/stock"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withSuccess(STOCK_REDUCED_JSON, MediaType.APPLICATION_JSON));

            mockServer.expect(requestTo(paymentBaseUrl + "/payments"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"message\":\"Service unavailable\"}"));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.status").value(502));

            // Order must be marked FAILED in the DB
            Order order = orderRepository.findAll().get(0);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        }
    }

    // =========================================================================
    // Product not found
    // =========================================================================

    @Nested
    @DisplayName("Product not found — product-service returns 404")
    class ProductNotFound {

        @Test
        @DisplayName("returns 404 when product-service does not know the product")
        void placeOrder_productNotFound_returns404() throws Exception {
            mockServer.expect(requestTo(productBaseUrl + "/products/99"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"message\":\"Product not found with id: 99\"}"));

            OrderRequest request = OrderRequest.builder()
                    .userId(42L).productId(99L).quantity(1).paymentMethod("CREDIT_CARD")
                    .build();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value(containsString("99")));

            assertThat(orderRepository.count()).isZero();
        }
    }

    // =========================================================================
    // Input validation
    // =========================================================================

    @Nested
    @DisplayName("Input validation — @Valid on OrderRequest")
    class InputValidation {

        @Test
        @DisplayName("returns 400 when userId is null")
        void placeOrder_nullUserId_returns400() throws Exception {
            OrderRequest request = OrderRequest.builder()
                    .userId(null).productId(1L).quantity(1).paymentMethod("CREDIT_CARD")
                    .build();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.userId").value("userId is required"));
        }

        @Test
        @DisplayName("returns 400 when productId is null")
        void placeOrder_nullProductId_returns400() throws Exception {
            OrderRequest request = OrderRequest.builder()
                    .userId(1L).productId(null).quantity(1).paymentMethod("CREDIT_CARD")
                    .build();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.productId").value("productId is required"));
        }

        @Test
        @DisplayName("returns 400 when quantity is zero")
        void placeOrder_zeroQuantity_returns400() throws Exception {
            OrderRequest request = OrderRequest.builder()
                    .userId(1L).productId(1L).quantity(0).paymentMethod("CREDIT_CARD")
                    .build();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.quantity")
                            .value("quantity must be at least 1"));
        }

        @Test
        @DisplayName("returns 400 when paymentMethod is blank")
        void placeOrder_blankPaymentMethod_returns400() throws Exception {
            OrderRequest request = OrderRequest.builder()
                    .userId(1L).productId(1L).quantity(2).paymentMethod("")
                    .build();

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.paymentMethod").isNotEmpty());
        }

        @Test
        @DisplayName("returns 400 when request body is missing")
        void placeOrder_emptyBody_returns400() throws Exception {
            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        }
    }
}
