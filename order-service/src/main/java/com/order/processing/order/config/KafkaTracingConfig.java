package com.order.processing.order.config;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Enables Spring Kafka's built-in Micrometer Observation support so that
 * distributed trace context (B3 headers) is automatically propagated through
 * every Kafka producer record and extracted from every consumer record.
 *
 * <h3>Mechanism (Spring Kafka 3 + Spring Boot 3)</h3>
 * <p>Spring Kafka 3.x ships a native {@code KafkaTemplateObservation} /
 * {@code KafkaListenerObservation} integration that is activated by setting
 * {@code observationEnabled = true} on the {@link KafkaTemplate} and
 * {@link ConcurrentKafkaListenerContainerFactory} beans.  When those flags are
 * set and a Micrometer {@link ObservationRegistry} is in the context, Spring
 * Kafka wraps every send/receive call in a Micrometer {@code Observation} which
 * in turn (via {@code micrometer-tracing-bridge-brave} already on the classpath)
 * injects / extracts B3 headers from each Kafka record's headers map.
 *
 * <p>This is the correct approach for Spring Boot 3 — it does NOT require the
 * separate {@code brave-instrumentation-kafka-clients} artifact.
 *
 * <h3>Header propagation chain</h3>
 * <pre>
 *   HTTP request  →  gateway-service  →  order-service   (B3 headers on HTTP)
 *                                              │
 *                                    Kafka produce: order-placed
 *                                    (b3 header injected into record)
 *                                              │
 *                                        product-service
 *                                    (b3 header extracted, same traceId)
 *                                              │
 *                                    Kafka produce: product-reserved
 *                                    (b3 header re-injected)
 *                                              │
 *                                        order-service
 *                                    (b3 header extracted, same traceId)
 *                                              │
 *                                    Kafka produce: payment-initiated
 *                                    (b3 header re-injected)
 *                                              │
 *                                        payment-service
 *                                    (b3 header extracted, same traceId)
 *                                              │
 *                                    Kafka produce: payment-completed
 *                                    (b3 header re-injected)
 *                                              │
 *                                        order-service
 *                                    (b3 header extracted, same traceId)
 * </pre>
 *
 * <p>All spans share the same {@code traceId}, producing one waterfall trace
 * in Zipkin spanning four services and six Kafka hops.
 *
 * <h3>Zipkin span names produced</h3>
 * <pre>
 *   kafka send  order-placed           (KafkaTemplate.send in order-service)
 *   kafka receive  order-placed        (KafkaListener in product-service)
 *   kafka send  product-reserved       (KafkaTemplate.send in product-service)
 *   kafka receive  product-reserved    (KafkaListener in order-service)
 *   kafka send  payment-initiated      (KafkaTemplate.send in order-service)
 *   kafka receive  payment-initiated   (KafkaListener in payment-service)
 *   kafka send  payment-completed      (KafkaTemplate.send in payment-service)
 *   kafka receive  payment-completed   (KafkaListener in order-service)
 * </pre>
 */
@Slf4j
@Configuration
public class KafkaTracingConfig {

    /**
     * BeanPostProcessor that enables Micrometer Observation on both the
     * auto-configured {@link KafkaTemplate} and
     * {@link ConcurrentKafkaListenerContainerFactory} beans after they are
     * created by Spring Boot's Kafka autoconfiguration.
     *
     * <p>Using a {@link BeanPostProcessor} lets us intercept the beans that
     * Spring Boot auto-configures from {@code application.yml} properties,
     * without having to redeclare all the producer/consumer factory settings.
     *
     * @param observationRegistry the Micrometer registry provided by
     *        {@code micrometer-tracing-bridge-brave} autoconfiguration
     */
    @Bean
    public static BeanPostProcessor kafkaObservationEnabler(ObservationRegistry observationRegistry) {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {

                // ── Producer side: KafkaTemplate ───────────────────────────
                // Spring Boot auto-configures exactly one KafkaTemplate bean.
                // Setting observationEnabled=true makes every send() call create
                // a child Observation (span) that injects B3 headers into the
                // Kafka record's Headers object before the bytes hit the wire.
                if (bean instanceof KafkaTemplate<?, ?> kt) {
                    kt.setObservationEnabled(true);
                    log.info("[Tracing] KafkaTemplate observation enabled (bean='{}')", beanName);
                }

                // ── Consumer side: ConcurrentKafkaListenerContainerFactory ─
                // Spring Boot auto-configures exactly one factory bean.
                // Setting observationEnabled=true makes every @KafkaListener
                // invocation extract B3 headers from the received record and
                // continue the existing trace (rather than starting a new one).
                if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                    factory.getContainerProperties().setObservationEnabled(true);
                    log.info("[Tracing] KafkaListenerContainerFactory observation enabled (bean='{}')", beanName);
                }

                return bean;
            }
        };
    }
}
