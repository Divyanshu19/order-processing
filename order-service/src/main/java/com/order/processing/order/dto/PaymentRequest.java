package com.order.processing.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request body sent to payment-service's POST /payments.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRequest {

    private Long orderId;
    private Long userId;
    private BigDecimal amount;
    private String paymentMethod;   // e.g. "CREDIT_CARD", "DIGITAL_WALLET"
}
