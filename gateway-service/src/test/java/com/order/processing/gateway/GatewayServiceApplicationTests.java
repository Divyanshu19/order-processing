package com.order.processing.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-tests for the Gateway application context.
 *
 * <p>Uses {@code webEnvironment = NONE} so no actual HTTP server is started and
 * no downstream services need to be reachable.  The tests only verify that:
 * <ul>
 *   <li>The Spring context loads successfully.</li>
 *   <li>All three expected routes are registered in the {@link RouteLocator}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class GatewayServiceApplicationTests {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void contextLoads() {
        // If the context fails to start the test itself fails – no assertion needed.
    }

    @Test
    void allThreeRoutesAreRegistered() {
        List<String> routeIds = routeLocator.getRoutes()
                .map(route -> route.getId())
                .collectList()
                .block();

        assertThat(routeIds)
                .as("All three core routes must be present")
                .contains("product-route", "order-route", "payment-route");
    }
}
