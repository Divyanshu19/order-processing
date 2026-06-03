package com.order.processing.order.client;

import com.order.processing.order.dto.ProductResponse;
import com.order.processing.order.dto.StockUpdateRequest;
import com.order.processing.order.exception.InsufficientStockException;
import com.order.processing.order.exception.ProductNotFoundException;
import com.order.processing.order.exception.ProductServiceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Component
public class ProductServiceClient {

    private final RestTemplate restTemplate;
    private final WebClient productServiceWebClient;

    @Value("${product-service.base-url}")
    private String productServiceBaseUrl;

    public ProductServiceClient(RestTemplate restTemplate,
                                @Qualifier("productServiceWebClient") WebClient productServiceWebClient) {
        this.restTemplate = restTemplate;
        this.productServiceWebClient = productServiceWebClient;
    }

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

    // ─────────────────────────────────────────────────────────────────────────
    // Retry + Circuit-Breaker protected call  (used by GET /orders/{id})
    //
    // Decoration order (outer → inner):
    //   @CircuitBreaker  →  @Retry  →  WebClient call
    //
    // This means: Retry exhausts ALL attempts first; only the final failure
    // (after all retries) is reported to the Circuit Breaker as one event.
    // A transient blip that succeeds on attempt 2 never trips the circuit.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches product details via {@code WebClient} protected by both a
     * Resilience4j <b>Retry</b> and a <b>Circuit Breaker</b>.
     *
     * <h3>Retry layer (inner)</h3>
     * <ul>
     *   <li>Up to <b>3 attempts</b> (1 original + 2 retries) on {@link ProductServiceException}.</li>
     *   <li><b>Exponential back-off</b>: 500 ms → 1 000 ms → 2 000 ms (capped).</li>
     *   <li>After all attempts fail → {@link #getProductRetryFallback} re-throws
     *       as {@link ProductServiceException} so the Circuit Breaker records
     *       exactly <em>one</em> failure for the entire retry sequence.</li>
     * </ul>
     *
     * <h3>Circuit Breaker layer (outer)</h3>
     * <ul>
     *   <li><b>CLOSED</b>   — calls proceed normally; failures are tallied.</li>
     *   <li><b>OPEN</b>     — short-circuits immediately to {@link #getProductFallback};
     *       product-service is not contacted at all (no retries attempted).</li>
     *   <li><b>HALF-OPEN</b> — a single probe call is allowed; success → CLOSED,
     *       failure → OPEN again.</li>
     * </ul>
     *
     * <p>A 404 from product-service is thrown as {@link ProductNotFoundException}
     * and is <em>ignored</em> by both Retry and Circuit Breaker (configured in
     * {@code application.yml}) — retrying a definitive "not found" is pointless.
     *
     * @param productId the ID of the product to fetch
     * @return the {@link ProductResponse} on success, or {@code null} via CB fallback
     */
    @CircuitBreaker(name = "productService", fallbackMethod = "getProductFallback")
    @Retry(name = "productService", fallbackMethod = "getProductRetryFallback")
    public ProductResponse getProductByIdWithCircuitBreaker(Long productId) {
        log.info("[CB+Retry] Calling product-service for productId={}", productId);

        try {
            return productServiceWebClient.get()
                    .uri("/products/{id}", productId)
                    .retrieve()
                    .onStatus(
                            status -> status.value() == 404,
                            response -> response.bodyToMono(String.class)
                                    .map(body -> new ProductNotFoundException(productId)))
                    .bodyToMono(ProductResponse.class)
                    .block();  // block so both Retry and CircuitBreaker track the outcome

        } catch (ProductNotFoundException ex) {
            // 404 is a definitive business error — rethrow immediately, skip retries
            throw ex;

        } catch (WebClientResponseException ex) {
            log.error("[CB+Retry] product-service returned HTTP {} for productId={}: {}",
                    ex.getStatusCode(), productId, ex.getResponseBodyAsString());
            // Wrap as ProductServiceException so Retry picks it up per retry-exceptions config
            throw new ProductServiceException(
                    "Product service returned an error: " + ex.getStatusCode(), ex);

        } catch (Exception ex) {
            log.error("[CB+Retry] Unexpected error calling product-service for productId={}: {}",
                    productId, ex.getMessage());
            throw new ProductServiceException(
                    "Product service is currently unavailable.", ex);
        }
    }

    /**
     * <b>Retry fallback</b> — invoked by Resilience4j after all retry attempts
     * for {@link #getProductByIdWithCircuitBreaker} are exhausted.
     *
     * <p>Re-throws the root cause as a {@link ProductServiceException} so that
     * the <em>single</em> terminal failure propagates up to the Circuit Breaker,
     * which counts it as one failure event (not three separate ones).
     *
     * <p><b>Fallback method signature rule:</b> must match the protected method's
     * parameter list exactly, with {@link io.github.resilience4j.retry.MaxRetriesExceededException}
     * appended as the last parameter.
     *
     * @param productId the product ID that was requested
     * @param ex        the {@code MaxRetriesExceededException} wrapping the last failure
     * @throws ProductServiceException always — allows the CB to count the failure
     */
    public ProductResponse getProductRetryFallback(
            Long productId,
            io.github.resilience4j.retry.MaxRetriesExceededException ex) {

        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        log.warn("[Retry] All {} attempts exhausted for productId={}. Last error: {}",
                3, productId, cause.getMessage());

        // Re-throw so the Circuit Breaker above can count this as one failure
        throw new ProductServiceException(
                "Product service did not respond after retries. Last cause: " + cause.getMessage(),
                cause);
    }

    /**
     * <b>Circuit Breaker fallback</b> — invoked when the circuit is OPEN
     * (call rejected before reaching product-service) OR when the retry
     * fallback's re-thrown exception reaches the CB.
     *
     * <p>Returns {@code null} so the service layer sets
     * {@code productServiceAvailable = false} in the enriched response,
     * keeping the API available even when product-service is fully down.
     *
     * @param productId the product ID that was requested
     * @param ex        the triggering exception (e.g., {@link ProductServiceException}
     *                  from exhausted retries, or
     *                  {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}
     *                  when the circuit is already OPEN)
     * @return {@code null} — signals graceful degradation to the service layer
     */
    public ProductResponse getProductFallback(Long productId, Throwable ex) {
        log.warn("[CB] Fallback triggered for productId={} — circuit may be OPEN. Cause: {}",
                productId, ex.getMessage());
        return null;
    }
}
