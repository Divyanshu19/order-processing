package com.order.processing.order.controller;

import com.order.processing.order.dto.OrderRequest;
import com.order.processing.order.dto.OrderResponse;
import com.order.processing.order.dto.OrderWithProductResponse;
import com.order.processing.order.security.AuthenticatedUser;
import com.order.processing.order.service.OrderService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * POST /api/orders
     *
     * <p>Places a new order on behalf of the authenticated user.
     *
     * <p><strong>Security</strong>: {@code userId} is NOT read from the request body.
     * It is extracted from the verified JWT {@code uid} claim via
     * {@code @AuthenticationPrincipal}, making it impossible for a caller to
     * create orders on behalf of another user.
     *
     * @param authenticatedUser principal populated by {@link com.order.processing.order.security.JwtAuthFilter}
     * @param request           body containing productId, quantity, and paymentMethod
     * @return 201 Created with the persisted {@link OrderResponse}
     */
    @Timed(
        value       = "http_orders_create_seconds",
        description = "HTTP POST /orders — end-to-end handler duration including product validation, DB write, and Kafka publish",
        percentiles = {0.50, 0.95, 0.99},
        histogram   = true
    )
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody OrderRequest request) {

        Long userId = authenticatedUser.userId();
        log.info("POST /orders - userId={} (from JWT), productId={}, quantity={}",
                userId, request.getProductId(), request.getQuantity());

        OrderResponse response = orderService.createOrder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/orders/{id}
     *
     * <p>Returns the order enriched with live product details fetched from
     * product-service via a Resilience4j circuit-breaker-protected WebClient call.
     *
     * <p><b>Circuit Breaker behaviour:</b>
     * <ul>
     *   <li><b>CLOSED</b>  — product details included in the response.</li>
     *   <li><b>OPEN / HALF-OPEN</b> — order is returned with
     *       {@code "productServiceAvailable": false} and {@code "product": null}
     *       so the API remains available even when product-service is down.</li>
     * </ul>
     *
     * @param id the order primary key
     * @return 200 OK with {@link OrderWithProductResponse};
     *         404 if the order does not exist
     */
    @Timed(
        value       = "http_orders_get_seconds",
        description = "HTTP GET /orders/{id} — includes CB-protected product-service enrichment call",
        percentiles = {0.50, 0.95, 0.99},
        histogram   = true
    )
    @GetMapping("/{id}")
    public ResponseEntity<OrderWithProductResponse> getOrder(@PathVariable Long id) {
        log.info("GET /orders/{}", id);
        OrderWithProductResponse response = orderService.getOrderWithProduct(id);
        return ResponseEntity.ok(response);
    }
}
