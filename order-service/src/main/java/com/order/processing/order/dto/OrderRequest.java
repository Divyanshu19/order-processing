package com.order.processing.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbound payload for {@code POST /orders}.
 *
 * <p><strong>Security note</strong>: {@code userId} is intentionally absent.
 * The user's identity is extracted from the verified JWT {@code uid} claim by
 * {@link com.order.processing.order.security.JwtAuthFilter} and injected into
 * the controller via {@code @AuthenticationPrincipal}.  Accepting a
 * client-supplied {@code userId} in the body would allow any authenticated user
 * to place orders on behalf of any other user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderRequest {

    @NotNull(message = "productId is required")
    private Long productId;

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;

    @NotBlank(message = "paymentMethod is required (e.g. CREDIT_CARD, DIGITAL_WALLET)")
    private String paymentMethod;
}
