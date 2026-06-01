package com.order.processing.order.health;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component("kafkaHealth")
public class KafkaHealthIndicator implements HealthIndicator {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Override
    public Health health() {
        try (AdminClient adminClient = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {

            adminClient.describeCluster()
                    .clusterId()
                    .get(5, TimeUnit.SECONDS);

            log.info("Kafka health check passed - broker reachable at {}", bootstrapServers);
            return Health.up()
                    .withDetail("status", "Kafka broker is reachable")
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();

        } catch (Exception e) {
            log.error("Kafka health check failed: {}", e.getMessage(), e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        }
    }
}
