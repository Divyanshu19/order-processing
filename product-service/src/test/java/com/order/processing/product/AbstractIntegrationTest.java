package com.order.processing.product;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shared base for all integration tests.
 *
 * <p>Uses an H2 in-memory database configured by
 * {@code application-integration-test.properties} — no Docker socket,
 * no Testcontainers, no external services required.
 *
 * <p>Profile {@code integration-test} activates the properties file which:
 * <ul>
 *   <li>Switches the datasource to H2 (MODE=PostgreSQL)</li>
 *   <li>Sets {@code spring.jpa.hibernate.ddl-auto=create-drop}</li>
 *   <li>Disables Kafka auto-configuration and health checks</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
public abstract class AbstractIntegrationTest {
    // No Testcontainers, no @Container, no @DynamicPropertySource.
    // All configuration is driven by application-integration-test.properties.
}
