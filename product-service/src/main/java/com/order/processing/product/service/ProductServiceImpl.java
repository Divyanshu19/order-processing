package com.order.processing.product.service;

import com.order.processing.product.dto.ProductRequest;
import com.order.processing.product.dto.ProductResponse;
import com.order.processing.product.dto.StockUpdateRequest;
import com.order.processing.product.entity.Product;
import com.order.processing.product.exception.DuplicateSkuException;
import com.order.processing.product.exception.InsufficientStockException;
import com.order.processing.product.exception.ProductNotFoundException;
import com.order.processing.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    @Override
    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        log.debug("Creating product with SKU: {}", request.getSku());

        if (productRepository.existsBySku(request.getSku())) {
            throw new DuplicateSkuException(request.getSku());
        }

        Product product = Product.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .stockQuantity(request.getStockQuantity())
                .build();

        Product saved = productRepository.save(product);
        log.info("Created product id={} SKU={}", saved.getId(), saved.getSku());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getProductById(Long id) {
        log.debug("Fetching product id={}", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        log.debug("Fetching all products");
        return productRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public ProductResponse reduceStock(Long id, StockUpdateRequest request) {
        log.debug("Reducing stock for product id={} by {}", id, request.getQuantity());

        // pessimistic lock not needed — @Version on Product handles concurrent deductions
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        int available = product.getStockQuantity();
        int toReduce  = request.getQuantity();

        if (toReduce > available) {
            throw new InsufficientStockException(id, toReduce, available);
        }

        product.setStockQuantity(available - toReduce);
        // saveAndFlush forces the UPDATE SQL to run immediately so Hibernate
        // refreshes the @Version field in memory (0 → 1) before toResponse()
        // reads it. With plain save(), the flush is deferred to transaction
        // commit — after toResponse() has already captured version=0.
        Product saved = productRepository.saveAndFlush(product);
        log.info("Reduced stock for product id={}: {} -> {}", id, available, saved.getStockQuantity());
        return toResponse(saved);
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stockQuantity(product.getStockQuantity())
                .version(product.getVersion())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
