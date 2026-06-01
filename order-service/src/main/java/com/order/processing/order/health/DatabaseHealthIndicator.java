package com.order.processing.order.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Component("databaseHealth")
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public  Health health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection != null && !connection.isClosed()) {
                log.info("Database health check passed");
                return Health.up()
                    .withDetail("status", "Database connection is active")
                    .withDetail("database", "PostgreSQL")
                    .build();
            } else {
                return Health.down()
                    .withDetail("error", "Database connection is closed")
                    .build();
            }
        } catch (Exception e) {
            log.error("Database health check failed: {}", e.getMessage(), e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withException(e)
                .build();
        }
    }
}
