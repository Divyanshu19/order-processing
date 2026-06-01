package com.order.processing.payment.service;

import com.order.processing.payment.dto.PaymentRequest;
import com.order.processing.payment.dto.PaymentResponse;

public interface PaymentService {

    /**
     * Processes a payment for the given request.
     * Persists a Payment record with status SUCCESS and returns its details.
     *
     * @param request contains orderId, userId, amount, and paymentMethod
     * @return the persisted payment details including the generated paymentId and transactionId
     */
    PaymentResponse processPayment(PaymentRequest request);
}
