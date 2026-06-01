package com.order.processing.order.exception;

/**
 * Thrown when the product-service is unreachable or returns an unexpected error
 * while the order-service is fetching product details.
 */
public class ProductServiceException extends RuntimeException {

    public ProductServiceException(String message) {
        super(message);
    }

    public ProductServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
