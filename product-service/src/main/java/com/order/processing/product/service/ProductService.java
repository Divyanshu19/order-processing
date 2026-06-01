package com.order.processing.product.service;

import com.order.processing.product.dto.ProductRequest;
import com.order.processing.product.dto.ProductResponse;
import com.order.processing.product.dto.StockUpdateRequest;

import java.util.List;

public interface ProductService {

    ProductResponse createProduct(ProductRequest request);

    ProductResponse getProductById(Long id);

    List<ProductResponse> getAllProducts();

    ProductResponse reduceStock(Long id, StockUpdateRequest request);
}
