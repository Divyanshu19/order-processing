package com.order.processing.order.exception;

/**
 * Thrown when the requested quantity exceeds the product's available stock.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long productId, int available, int requested) {
        super(String.format(
                "Insufficient stock for product %d: requested %d but only %d available",
                productId, requested, available));
    }
}
