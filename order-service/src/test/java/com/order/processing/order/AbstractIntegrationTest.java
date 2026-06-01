package com.order.processing.order;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shared base for all order-service integration tests.
 *
 * <p>Uses an H2 in-memory database configured by
 * {@code application-integration-test.properties} — no Docker, no Testcontainers,
 * no live PostgreSQL required.
 *
 * <p>Profile {@code integration-test} activates the properties file which:
 * <ul>
 *   <li>Switches the datasource to H2 (MODE=PostgreSQL)</li>
 *   <li>Sets {@code spring.jpa.hibernate.ddl-auto=create-drop}</li>
 *   <li>Disables Kafka auto-configuration and health checks</li>
 *   <li>Sets stub base-URLs consumed by {@code MockRestServiceServer}</li>
 * </ul>
 *
 * <p>External HTTP calls (to product-service and payment-service) are intercepted
 * by {@code MockRestServiceServer} in each test class — no real services needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
public abstract class AbstractIntegrationTest {
    // Intentionally empty — configuration is entirely properties-driven.
}
