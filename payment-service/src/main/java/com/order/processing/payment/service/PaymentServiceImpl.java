package com.order.processing.payment.service;

import com.order.processing.payment.dto.PaymentRequest;
import com.order.processing.payment.dto.PaymentResponse;
import com.order.processing.payment.entity.Payment;
import com.order.processing.payment.entity.Payment.PaymentStatus;
import com.order.processing.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;

    /**
     * Dummy payment processing:
     * 1. Generate a unique transactionId (UUID).
     * 2. Persist a Payment record with status SUCCESS.
     * 3. Return the full PaymentResponse including the DB-generated paymentId.
     *
     * Replace step 2 with a real payment-gateway call when integrating a live provider.
     */
    @Override
    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("Processing payment for orderId={}, userId={}, amount={}, method={}",
                request.getOrderId(), request.getUserId(),
                request.getAmount(), request.getPaymentMethod());

        String transactionId = UUID.randomUUID().toString();

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .paymentMethod(request.getPaymentMethod())
                .transactionId(transactionId)
                .status(PaymentStatus.SUCCESS)
                .referenceNumber("REF-" + transactionId.substring(0, 8).toUpperCase())
                .remarks("Dummy payment processed successfully")
                .build();

        Payment saved = paymentRepository.save(payment);

        log.info("Payment persisted: paymentId={}, transactionId={}, status={}",
                saved.getId(), saved.getTransactionId(), saved.getStatus());

        return toResponse(saved, request.getUserId());
    }

    private PaymentResponse toResponse(Payment payment, Long userId) {
        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .userId(userId)
                .amount(payment.getAmount())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .transactionId(payment.getTransactionId())
                .message("Payment processed successfully")
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
