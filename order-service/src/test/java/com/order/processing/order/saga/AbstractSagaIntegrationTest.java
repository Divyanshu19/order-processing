package com.order.processing.order.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.order.processing.order.event.PaymentCompletedEvent;
import com.order.processing.order.event.PaymentFailedEvent;
import com.order.processing.order.event.ProductReservationFailedEvent;
import com.order.processing.order.event.ProductReservedEvent;
import com.order.processing.order.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Shared base for all order-service saga integration tests.
 *
 * <h2>Infrastructure</h2>
 * Two containers are started <em>once per test class</em> using JUnit 5's
 * {@code @Testcontainers} + {@code @Container} static field lifecycle:
 *
 * <ul>
 *   <li><b>PostgreSQL 15-alpine</b> — real relational DB; Flyway/Hibernate
 *       DDL creates the {@code orders} table automatically via
 *       {@code spring.jpa.hibernate.ddl-auto=create-drop}.</li>
 *   <li><b>Kafka (Confluent 7.5.0)</b> — the same image used in
 *       {@code docker-compose.yml}, so behaviour is identical to production.
 *       All six saga topics are auto-created by {@link
 *       com.order.processing.order.config.KafkaTopicConfig} at startup.</li>
 * </ul>
 *
 * <h2>Spring context wiring</h2>
 * {@link #overrideContainerProperties} injects each container's dynamic
 * connection URL/port into the Spring {@link DynamicPropertyRegistry} before
 * the application context is created.  This replaces the hardcoded
 * {@code localhost} values in {@code application.yml} without needing an
 * extra {@code application-test.yml} file.
 *
 * <h2>ProductServiceClient isolation</h2>
 * The order-service calls product-service synchronously via {@code RestTemplate}
 * when {@code POST /orders} is received (to validate the product and fetch its
 * price). Because product-service is <em>not</em> part of this test boundary,
 * it is stubbed by WireMock (configured in each subclass) so the HTTP request
 * is intercepted before it leaves the JVM.
 *
 * <h2>Saga event injection</h2>
 * Downstream saga steps (stock reservation, payment) are simulated by
 * publishing events directly to Kafka using the shared {@link #kafkaTemplate}.
 * This is the most realistic possible test: the full {@link
 * com.order.processing.order.event.SagaEventListener} deserialization and
 * state-machine logic is exercised, not just mocked.
 *
 * <h2>Async assertions</h2>
 * All Kafka consumer callbacks are asynchronous. Subclasses use
 * {@link org.awaitility.Awaitility} with a 10-second ceiling to poll the
 * database until the expected {@code OrderStatus} appears, or fail the test
 * with a clear diagnostic message.
 *
 * <h2>Context isolation</h2>
 * {@code @DirtiesContext} is applied at class level so each test class starts
 * with a clean Spring context and empty database tables, preventing saga events
 * left in Kafka partitions from one class polluting another.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("saga-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractSagaIntegrationTest {

    // ── Shared containers (started once per JVM, reused across test classes) ──

    /**
     * PostgreSQL 15-alpine — matches the version in {@code docker-compose.yml}.
     * The {@code @Container} + {@code static} combination means Testcontainers
     * starts this container once for the entire test class, not per test method,
     * keeping the suite fast.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("order_db_test")
                    .withUsername("order_user")
                    .withPassword("order_password");

    /**
     * Confluent Kafka 7.5.0 — matches the image in {@code docker-compose.yml}.
     * KRaft mode is not available in the Testcontainers Confluent image at this
     * version, so ZooKeeper mode is used automatically. For the purposes of
     * saga testing the broker behaviour is identical.
     */
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    // ── Dynamic property injection ────────────────────────────────────────────

    /**
     * Wires each container's dynamically allocated port into the Spring
     * {@link DynamicPropertyRegistry} so the application context is created
     * with correct connection strings — no hardcoded ports, no race conditions.
     *
     * <p>Called by Spring Test <em>before</em> the application context is built.
     */
    @DynamicPropertySource
    static void overrideContainerProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL connection
        registry.add("spring.datasource.url",        POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",   POSTGRES::getUsername);
        registry.add("spring.datasource.password",   POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");

        // Kafka bootstrap server (single broker, ephemeral port)
        registry.add("spring.kafka.bootstrap-servers",          KAFKA::getBootstrapServers);
        registry.add("spring.kafka.producer.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    // ── Injected test helpers ─────────────────────────────────────────────────

    /**
     * Used to send saga events directly to the real Kafka broker, simulating
     * what product-service and payment-service would publish.
     *
     * <p>The same auto-configured {@link KafkaTemplate} that the order-service
     * itself uses — so serialization/deserialization is exercised end-to-end.
     */
    @Autowired
    protected KafkaTemplate<String, Object> kafkaTemplate;

    /** Full HTTP client bound to the test server's random port. */
    @Autowired
    protected TestRestTemplate restTemplate;

    /** Direct repository access for DB state assertions. */
    @Autowired
    protected OrderRepository orderRepository;

    /** JSON mapper shared with the running application context. */
    @Autowired
    protected ObjectMapper objectMapper;

    // ── Topic name bindings (from application.yml) ───────────────────────────

    @Value("${kafka.topics.product-reserved}")
    protected String productReservedTopic;

    @Value("${kafka.topics.product-reservation-failed}")
    protected String productReservationFailedTopic;

    @Value("${kafka.topics.payment-completed}")
    protected String paymentCompletedTopic;

    @Value("${kafka.topics.payment-failed}")
    protected String paymentFailedTopic;

    // ── Shared event factory helpers ──────────────────────────────────────────

    /**
     * Builds and publishes a {@link ProductReservedEvent} to the
     * {@code product-reserved} topic, simulating what product-service sends
     * after successfully reducing stock.
     *
     * @param orderId       the order to advance
     * @param productId     the product whose stock was reserved
     * @param quantity      units reserved
     * @param remainingStock stock level after deduction
     */
    protected void publishProductReserved(Long orderId, Long productId,
                                          int quantity, int remainingStock) {
        ProductReservedEvent event = ProductReservedEvent.builder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .remainingStock(remainingStock)
                .reservedAt(LocalDateTime.now())
                .build();
        kafkaTemplate.send(productReservedTopic, orderId.toString(), event);
    }

    /**
     * Builds and publishes a {@link ProductReservationFailedEvent}, simulating
     * what product-service sends when it cannot reserve stock.
     *
     * @param orderId           the order to compensate
     * @param productId         the product that failed
     * @param requestedQuantity the quantity that was requested
     * @param reason            human-readable failure reason
     */
    protected void publishProductReservationFailed(Long orderId, Long productId,
                                                    int requestedQuantity, String reason) {
        ProductReservationFailedEvent event = ProductReservationFailedEvent.builder()
                .orderId(orderId)
                .productId(productId)
                .requestedQuantity(requestedQuantity)
                .availableStock(0)
                .reason(reason)
                .failedAt(LocalDateTime.now())
                .build();
        kafkaTemplate.send(productReservationFailedTopic, orderId.toString(), event);
    }

    /**
     * Builds and publishes a {@link PaymentCompletedEvent}, simulating what
     * payment-service sends after a successful charge.
     *
     * @param orderId       the order whose payment was completed
     * @param amount        amount charged
     * @param transactionId synthetic transaction identifier
     */
    protected void publishPaymentCompleted(Long orderId, BigDecimal amount,
                                            String transactionId) {
        PaymentCompletedEvent event = PaymentCompletedEvent.builder()
                .orderId(orderId)
                .paymentId(System.currentTimeMillis())   // unique per call
                .transactionId(transactionId)
                .amount(amount)
                .completedAt(LocalDateTime.now())
                .build();
        kafkaTemplate.send(paymentCompletedTopic, orderId.toString(), event);
    }

    /**
     * Builds and publishes a {@link PaymentFailedEvent}, simulating what
     * payment-service sends when a charge is declined.
     *
     * @param orderId the order whose payment failed
     * @param amount  amount that could not be charged
     * @param reason  human-readable failure reason (e.g. "Card declined")
     */
    protected void publishPaymentFailed(Long orderId, BigDecimal amount, String reason) {
        PaymentFailedEvent event = PaymentFailedEvent.builder()
                .orderId(orderId)
                .amount(amount)
                .reason(reason)
                .failedAt(LocalDateTime.now())
                .build();
        kafkaTemplate.send(paymentFailedTopic, orderId.toString(), event);
    }
}
