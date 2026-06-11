package com.order.processing.payment.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.order.processing.payment.event.PaymentInitiatedEvent;
import com.order.processing.payment.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Shared base for all payment-service saga integration tests.
 *
 * <h2>Infrastructure</h2>
 * Two containers are started <em>once per test class</em> using JUnit 5's
 * {@code @Testcontainers} + {@code @Container} static field lifecycle:
 *
 * <ul>
 *   <li><b>PostgreSQL 15-alpine</b> — real relational DB; Hibernate DDL
 *       creates the {@code payments} table automatically via
 *       {@code spring.jpa.hibernate.ddl-auto=create-drop}.</li>
 *   <li><b>Kafka (Confluent 7.5.0)</b> — the same image used in
 *       {@code docker-compose.yml}. Topics are auto-created by Kafka's
 *       {@code auto.create.topics.enable} setting.</li>
 * </ul>
 *
 * <h2>Spring context wiring</h2>
 * {@link #overrideContainerProperties} injects each container's dynamic
 * connection URL/port into the Spring {@link DynamicPropertyRegistry} before
 * the application context is created.
 *
 * <h2>Saga event injection</h2>
 * Tests inject {@link PaymentInitiatedEvent} events directly to Kafka via the
 * shared {@link #kafkaTemplate}, which causes the real
 * {@link com.order.processing.payment.event.PaymentInitiatedEventListener} to
 * fire. The result is verified by asserting against
 * {@link com.order.processing.payment.repository.PaymentRepository}.
 *
 * <h2>Async assertions</h2>
 * All Kafka consumer callbacks are asynchronous. Subclasses must use
 * {@link org.awaitility.Awaitility} with appropriate timeouts to avoid
 * busy-spinning or fragile {@code Thread.sleep} calls.
 *
 * <h2>Context isolation</h2>
 * {@code @DirtiesContext} ensures each test class starts with a fresh Spring
 * context and clean DB tables.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("payment-saga-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractPaymentSagaIntegrationTest {

    // ── Shared containers (started once per test class) ───────────────────────

    /**
     * PostgreSQL 15-alpine — matches the version in {@code docker-compose.yml}.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("payment_db_test")
                    .withUsername("payment_user")
                    .withPassword("payment_password");

    /**
     * Confluent Kafka 7.5.0 — matches the image in {@code docker-compose.yml}.
     */
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    // ── Dynamic property injection ────────────────────────────────────────────

    /**
     * Wires container ports into the Spring context before it starts.
     */
    @DynamicPropertySource
    static void overrideContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",        POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",   POSTGRES::getUsername);
        registry.add("spring.datasource.password",   POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");

        registry.add("spring.kafka.bootstrap-servers",          KAFKA::getBootstrapServers);
        registry.add("spring.kafka.producer.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    // ── Injected test helpers ─────────────────────────────────────────────────

    /**
     * Used to publish {@link PaymentInitiatedEvent} messages directly to Kafka,
     * simulating what the order-service would publish after stock is reserved.
     */
    @Autowired
    protected KafkaTemplate<String, Object> kafkaTemplate;

    /** Direct repository access for asserting DB state after async processing. */
    @Autowired
    protected PaymentRepository paymentRepository;

    /** JSON mapper shared with the running application context. */
    @Autowired
    protected ObjectMapper objectMapper;

    // ── Topic name bindings ───────────────────────────────────────────────────

    @Value("${kafka.topics.payment-initiated}")
    protected String paymentInitiatedTopic;

    @Value("${kafka.topics.payment-completed}")
    protected String paymentCompletedTopic;

    @Value("${kafka.topics.payment-failed}")
    protected String paymentFailedTopic;

    // ── Shared event factory helper ───────────────────────────────────────────

    /**
     * Publishes a {@link PaymentInitiatedEvent} to the {@code payment-initiated}
     * topic, simulating what order-service sends after product stock is reserved.
     *
     * @param orderId        the order to process payment for
     * @param userId         the user being charged
     * @param amount         the amount to charge
     * @param paymentMethod  payment method string (e.g. "CREDIT_CARD")
     */
    protected void publishPaymentInitiated(Long orderId, Long userId,
                                           BigDecimal amount, String paymentMethod) {
        PaymentInitiatedEvent event = PaymentInitiatedEvent.builder()
                .orderId(orderId)
                .userId(userId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .initiatedAt(LocalDateTime.now())
                .build();
        kafkaTemplate.send(paymentInitiatedTopic, orderId.toString(), event);
    }
}
