package com.order.processing.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares all Kafka topics for the order-processing system.
 *
 * <p>Topic creation is centralised here in the order-service because it is the
 * entry point of every saga. Spring Kafka's {@link TopicBuilder} will create the
 * topic if it does not exist, and leave it unchanged if it already does — making
 * this idempotent across restarts.
 *
 * <p>Topic names are externalised to {@code application.yml} so they can be
 * overridden per environment (dev / staging / prod) without recompilation.
 *
 * <pre>
 * Topic                          Producer          Consumers
 * ─────────────────────────��───  ────────────────  ─────────────────────────
 * order-placed                   order-service     product-service
 * product-reserved               product-service   order-service
 * product-reservation-failed     product-service   order-service
 * payment-initiated              order-service     payment-service
 * payment-completed              payment-service   order-service
 * payment-failed                 payment-service   order-service
 * </pre>
 */
@Configuration
public class KafkaTopicConfig {

    /* ── Topic name bindings (from application.yml) ────────────────────────── */

    @Value("${kafka.topics.order-placed}")
    private String orderPlacedTopic;

    @Value("${kafka.topics.product-reserved}")
    private String productReservedTopic;

    @Value("${kafka.topics.product-reservation-failed}")
    private String productReservationFailedTopic;

    @Value("${kafka.topics.payment-initiated}")
    private String paymentInitiatedTopic;

    @Value("${kafka.topics.payment-completed}")
    private String paymentCompletedTopic;

    @Value("${kafka.topics.payment-failed}")
    private String paymentFailedTopic;

    /* ── Topic beans ────────────────────────────────────────────────────────── */

    /**
     * Published by: order-service  (after persisting the order with status PENDING).
     * Consumed by:  product-service (to trigger stock reservation).
     */
    @Bean
    public NewTopic orderPlacedTopic() {
        return TopicBuilder.name(orderPlacedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Published by: product-service (after successfully reducing stock).
     * Consumed by:  order-service   (to trigger payment initiation).
     */
    @Bean
    public NewTopic productReservedTopic() {
        return TopicBuilder.name(productReservedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Published by: product-service (when stock is insufficient or unavailable).
     * Consumed by:  order-service   (to mark the order CANCELLED).
     */
    @Bean
    public NewTopic productReservationFailedTopic() {
        return TopicBuilder.name(productReservationFailedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Published by: order-service  (after stock is reserved, to trigger payment).
     * Consumed by:  payment-service (to process the charge).
     */
    @Bean
    public NewTopic paymentInitiatedTopic() {
        return TopicBuilder.name(paymentInitiatedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Published by: payment-service (after a successful charge).
     * Consumed by:  order-service   (to mark the order CONFIRMED).
     */
    @Bean
    public NewTopic paymentCompletedTopic() {
        return TopicBuilder.name(paymentCompletedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Published by: payment-service (on charge failure or decline).
     * Consumed by:  order-service   (to mark the order FAILED).
     */
    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name(paymentFailedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
