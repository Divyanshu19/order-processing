package com.order.processing.product.config;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Enables Spring Kafka's built-in Micrometer Observation support for the
 * product-service, ensuring B3 trace context is:
 * <ul>
 *   <li><b>Extracted</b> from incoming {@code order-placed} Kafka records
 *       (so the consumer continues the trace started by order-service).</li>
 *   <li><b>Injected</b> into outgoing {@code product-reserved} /
 *       {@code product-reservation-failed} Kafka records.</li>
 * </ul>
 *
 * <p>See {@code order-service KafkaTracingConfig} for the full explanation of
 * the Spring Kafka 3 Observation mechanism.
 */
@Slf4j
@Configuration
public class KafkaTracingConfig {

    @Bean
    public static BeanPostProcessor kafkaObservationEnabler(ObservationRegistry observationRegistry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (bean instanceof KafkaTemplate<?, ?> kt) {
                    kt.setObservationEnabled(true);
                    log.info("[Tracing] KafkaTemplate observation enabled (bean='{}')", beanName);
                }
                if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                    factory.getContainerProperties().setObservationEnabled(true);
                    log.info("[Tracing] KafkaListenerContainerFactory observation enabled (bean='{}')", beanName);
                }
                return bean;
            }
        };
    }
}
