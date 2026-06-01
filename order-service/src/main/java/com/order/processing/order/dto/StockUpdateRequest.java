package com.order.processing.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body sent to product-service's PUT /products/{id}/stock.
 * Mirrors product-service's own StockUpdateRequest.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockUpdateRequest {

    private Integer quantity;
}
