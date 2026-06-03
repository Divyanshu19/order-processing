package com.order.processing.order.controller;

import com.order.processing.order.dto.OrderRequest;
import com.order.processing.order.dto.OrderResponse;
import com.order.processing.order.dto.OrderWithProductResponse;
import com.order.processing.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
     * Places a new order. Validates input, calls the product-service to verify
     * the product and check stock, then persists and returns the created order.
     *
     * @param request body containing userId, productId, and quantity
     * @return 201 Created with the persisted OrderResponse
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        log.info("POST /orders - userId={}, productId={}, quantity={}",
                request.getUserId(), request.getProductId(), request.getQuantity());
        OrderResponse response = orderService.createOrder(request);
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
    @GetMapping("/{id}")
    public ResponseEntity<OrderWithProductResponse> getOrder(@PathVariable Long id) {
        log.info("GET /orders/{}", id);
        OrderWithProductResponse response = orderService.getOrderWithProduct(id);
        return ResponseEntity.ok(response);
    }
}
