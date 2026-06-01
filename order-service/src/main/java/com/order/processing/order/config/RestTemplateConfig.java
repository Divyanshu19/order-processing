package com.order.processing.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * Shared RestTemplate bean used for synchronous inter-service HTTP calls.
     * Connect and read timeouts are configured via SimpleClientHttpRequestFactory
     * to prevent indefinite blocking if the product-service is slow or unreachable.
     *
     * NOTE: RestTemplateBuilder.connectTimeout(Duration) / readTimeout(Duration)
     * were removed in Spring Boot 3.2. Use SimpleClientHttpRequestFactory instead.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);   // 5 seconds
        factory.setReadTimeout(5_000);      // 5 seconds
        return new RestTemplate(factory);
    }
}
