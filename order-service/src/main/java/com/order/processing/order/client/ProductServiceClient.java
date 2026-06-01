package com.order.processing.order.client;

import com.order.processing.order.dto.ProductResponse;
import com.order.processing.order.dto.StockUpdateRequest;
import com.order.processing.order.exception.InsufficientStockException;
import com.order.processing.order.exception.ProductNotFoundException;
import com.order.processing.order.exception.ProductServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductServiceClient {

    private final RestTemplate restTemplate;

    @Value("${product-service.base-url}")
    private String productServiceBaseUrl;

    /**
     * Fetches a product by ID from the product-service via GET /products/{id}.
     *
     * @param productId the ID of the product to fetch
     * @return the product details
     * @throws ProductNotFoundException  if product-service returns HTTP 404
     * @throws ProductServiceException   if product-service is unreachable or returns any other error
     */
    public ProductResponse getProductById(Long productId) {
        String url = productServiceBaseUrl + "/products/" + productId;
        log.info("Fetching product from product-service: GET {}", url);

        try {
            ResponseEntity<ProductResponse> response =
                    restTemplate.getForEntity(url, ProductResponse.class);
            log.info("Received product response for productId={}: status={}",
                    productId, response.getStatusCode());
            return response.getBody();

        } catch (HttpClientErrorException.NotFound ex) {
            log.warn("Product not found in product-service for productId={}", productId);
            throw new ProductNotFoundException(productId);

        } catch (HttpClientErrorException ex) {
            log.error("Client error from product-service for productId={}: status={}, body={}",
                    productId, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new ProductServiceException(
                    "Product service returned an error: " + ex.getStatusCode(), ex);

        } catch (ResourceAccessException ex) {
            log.error("Cannot reach product-service at {} for productId={}: {}",
                    url, productId, ex.getMessage());
            throw new ProductServiceException(
                    "Product service is currently unavailable. Please try again later.", ex);
        }
    }

    /**
     * Reserves stock by calling product-service's PUT /products/{id}/stock.
     * Reduces the product's stockQuantity by the given quantity.
     *
     * @param productId the product whose stock should be reduced
     * @param quantity  the amount to deduct
     * @throws InsufficientStockException if product-service returns HTTP 409 (stock too low)
     * @throws ProductServiceException    if the call fails for any other reason
     */
    public void reduceStock(Long productId, Integer quantity) {
        String url = productServiceBaseUrl + "/products/" + productId + "/stock";
        log.info("Reserving stock at product-service: PUT {} (quantity={})", url, quantity);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<StockUpdateRequest> request =
                new HttpEntity<>(new StockUpdateRequest(quantity), headers);

        try {
            restTemplate.exchange(url, HttpMethod.PUT, request, ProductResponse.class);
            log.info("Stock successfully reduced for productId={} by {}", productId, quantity);

        } catch (HttpClientErrorException.Conflict ex) {
            log.warn("Insufficient stock on product-service for productId={}: {}", productId,
                    ex.getResponseBodyAsString());
            throw new InsufficientStockException(productId, 0, quantity);

        } catch (HttpClientErrorException ex) {
            log.error("Client error reducing stock for productId={}: status={}, body={}",
                    productId, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new ProductServiceException(
                    "Product service returned an error during stock reduction: " + ex.getStatusCode(), ex);

        } catch (ResourceAccessException ex) {
            log.error("Cannot reach product-service at {} to reduce stock: {}", url, ex.getMessage());
            throw new ProductServiceException(
                    "Product service is currently unavailable. Please try again later.", ex);
        }
    }
}
