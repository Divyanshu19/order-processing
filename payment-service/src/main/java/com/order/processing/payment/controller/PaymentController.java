package com.order.processing.payment.controller;

import com.order.processing.payment.dto.PaymentRequest;
import com.order.processing.payment.dto.PaymentResponse;
import com.order.processing.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * POST /api/payments
     *
     * Processes a payment for an order. Persists a Payment record with
     * status SUCCESS and returns the payment ID, transactionId, and details.
     *
     * @param request contains orderId, userId, amount, and paymentMethod
     * @return 201 Created with the persisted PaymentResponse
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @Valid @RequestBody PaymentRequest request) {
        log.info("POST /payments - orderId={}, userId={}, amount={}, method={}",
                request.getOrderId(), request.getUserId(),
                request.getAmount(), request.getPaymentMethod());

        PaymentResponse response = paymentService.processPayment(request);

        log.info("Payment created: paymentId={}, transactionId={}, status={}",
                response.getPaymentId(), response.getTransactionId(), response.getStatus());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
