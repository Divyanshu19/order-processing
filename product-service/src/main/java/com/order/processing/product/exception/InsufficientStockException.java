package com.order.processing.product.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long productId, int requested, int available) {
        super(String.format(
                "Insufficient stock for product id %d: requested %d, available %d",
                productId, requested, available));
    }
}
