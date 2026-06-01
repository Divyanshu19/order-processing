package com.order.processing.order.exception;

/**
 * Thrown when product-service returns HTTP 404 for the requested productId.
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long productId) {
        super("Product not found with id: " + productId);
    }
}
