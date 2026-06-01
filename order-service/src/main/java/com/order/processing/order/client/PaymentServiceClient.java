package com.order.processing.order.client;

import com.order.processing.order.dto.PaymentRequest;
import com.order.processing.order.dto.PaymentResponse;
import com.order.processing.order.exception.PaymentServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentServiceClient {

    private final RestTemplate restTemplate;

    @Value("${payment-service.base-url}")
    private String paymentServiceBaseUrl;

    /**
     * Charges the customer by calling payment-service's POST /payments.
     *
     * @param paymentRequest contains orderId, userId, amount, and paymentMethod
     * @return PaymentResponse with paymentId, status, transactionId, and details
     * @throws PaymentServiceException if payment-service returns a failure or is unreachable
     */
    public PaymentResponse charge(PaymentRequest paymentRequest) {
        String url = paymentServiceBaseUrl + "/payments";
        log.info("Calling payment-service: POST {} (orderId={}, amount={})",
                url, paymentRequest.getOrderId(), paymentRequest.getAmount());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<PaymentRequest> entity = new HttpEntity<>(paymentRequest, headers);

        try {
            ResponseEntity<PaymentResponse> response =
                    restTemplate.postForEntity(url, entity, PaymentResponse.class);

            PaymentResponse body = response.getBody();
            log.info("Payment response for orderId={}: status={}, transactionId={}",
                    paymentRequest.getOrderId(),
                    body != null ? body.getStatus() : "null",
                    body != null ? body.getTransactionId() : "null");

            if (body == null || !"SUCCESS".equalsIgnoreCase(body.getStatus())) {
                String reason = body != null ? body.getMessage() : "No response body";
                throw new PaymentServiceException("Payment was not successful: " + reason);
            }

            return body;

        } catch (PaymentServiceException ex) {
            throw ex;   // rethrow our own exception as-is

        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            log.error("HTTP error from payment-service for orderId={}: status={}, body={}",
                    paymentRequest.getOrderId(), ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new PaymentServiceException(
                    "Payment service returned an error: " + ex.getStatusCode(), ex);

        } catch (ResourceAccessException ex) {
            log.error("Cannot reach payment-service at {}: {}", url, ex.getMessage());
            throw new PaymentServiceException(
                    "Payment service is currently unavailable. Please try again later.", ex);
        }
    }
}
