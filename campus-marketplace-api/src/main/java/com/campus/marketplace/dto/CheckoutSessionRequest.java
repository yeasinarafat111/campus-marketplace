package com.campus.marketplace.dto;

import com.campus.marketplace.entity.PaymentPurpose;
import jakarta.validation.constraints.NotNull;

public record CheckoutSessionRequest(
        @NotNull(message = "listingId is required")
        Long listingId,

        @NotNull(message = "purpose is required")
        PaymentPurpose purpose
) {
}
