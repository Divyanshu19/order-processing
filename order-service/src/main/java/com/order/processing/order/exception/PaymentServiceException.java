package com.order.processing.order.exception;

/**
 * Thrown when the payment-service is unreachable or returns a non-success response
 * while the order-service is attempting to charge for an order.
 */
public class PaymentServiceException extends RuntimeException {

    public PaymentServiceException(String message) {
        super(message);
    }

    public PaymentServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
