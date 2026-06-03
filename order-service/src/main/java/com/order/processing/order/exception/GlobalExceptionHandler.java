package com.order.processing.order.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException ex) {
        log.warn("Order not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(buildError(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex) {
        log.warn("Product not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(buildError(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException ex) {
        log.warn("Insufficient stock: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(buildError(HttpStatus.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(ProductServiceException.class)
    public ResponseEntity<ErrorResponse> handleProductServiceException(ProductServiceException ex) {
        log.error("Product service communication error: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildError(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    /**
     * Handles Resilience4j {@link CallNotPermittedException}, thrown when the
     * circuit breaker for {@code productService} is in OPEN state and rejects
     * the call before it even reaches product-service.
     *
     * <p>This handler only fires when the fallback is NOT configured (or if
     * the exception escapes the fallback). In this project the fallback returns
     * {@code null} gracefully, so this handler acts as an extra safety net.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitBreakerOpen(CallNotPermittedException ex) {
        log.warn("Circuit breaker OPEN — call rejected for circuit '{}': {}",
                ex.getCausingCircuitBreakerName(), ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildError(HttpStatus.SERVICE_UNAVAILABLE,
                        "Product service is temporarily unavailable (circuit breaker open). " +
                        "Order data is still accessible without product details."));
    }

    /**
     * Safety-net handler for {@link MaxRetriesExceededException}.
     *
     * <p>In normal operation this is caught by the retry fallback
     * ({@code getProductRetryFallback}) and re-wrapped as a
     * {@link ProductServiceException} before reaching here. This handler
     * fires only if the exception somehow escapes the fallback chain.
     */
    @ExceptionHandler(MaxRetriesExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxRetriesExceeded(MaxRetriesExceededException ex) {
        log.error("All retry attempts exhausted for '{}': {}",
                ex.getMessage(), ex.getCause() != null ? ex.getCause().getMessage() : "n/a");
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildError(HttpStatus.SERVICE_UNAVAILABLE,
                        "Product service is unavailable after multiple retry attempts. " +
                        "Please try again later."));
    }

    @ExceptionHandler(PaymentServiceException.class)
    public ResponseEntity<ErrorResponse> handlePaymentServiceException(PaymentServiceException ex) {
        log.error("Payment service error: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(buildError(HttpStatus.BAD_GATEWAY, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            fieldErrors.put(field, error.getDefaultMessage());
        });
        log.warn("Validation failed: {}", fieldErrors);
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .fieldErrors(fieldErrors)
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"));
    }

    private ErrorResponse buildError(HttpStatus status, String message) {
        return ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
