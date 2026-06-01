package com.order.processing.product.controller;

import com.order.processing.product.dto.ProductRequest;
import com.order.processing.product.dto.ProductResponse;
import com.order.processing.product.dto.StockUpdateRequest;
import com.order.processing.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * POST /api/products
     * Create a new product. Returns 201 Created with the persisted product.
     */
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody ProductRequest request) {
        log.info("POST /products - SKU: {}", request.getSku());
        ProductResponse response = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/products/{id}
     * Fetch a single product by its primary key. Returns 404 if not found.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable Long id) {
        log.info("GET /products/{}", id);
        return ResponseEntity.ok(productService.getProductById(id));
    }

    /**
     * GET /api/products
     * Fetch all products. Returns an empty array when no products exist.
     */
    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        log.info("GET /products");
        return ResponseEntity.ok(productService.getAllProducts());
    }

    /**
     * PUT /api/products/{id}/stock
     * Reduce the stock quantity of a product by the given amount.
     * Returns 409 Conflict if stock is insufficient or an optimistic-lock
     * collision occurs (concurrent deduction by another request).
     */
    @PutMapping("/{id}/stock")
    public ResponseEntity<ProductResponse> reduceStock(
            @PathVariable Long id,
            @Valid @RequestBody StockUpdateRequest request) {
        log.info("PUT /products/{}/stock - reduce by {}", id, request.getQuantity());
        return ResponseEntity.ok(productService.reduceStock(id, request));
    }
}
