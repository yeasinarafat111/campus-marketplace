package com.campus.marketplace.dto;

import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;

/**
 * @param url    Stripe-hosted checkout page; redirect the user here
 * @param amount smallest currency unit (e.g. cents)
 */
public record CheckoutSessionResponse(
        Long paymentId,
        String sessionId,
        String url,
        long amount,
        String currency,
        PaymentPurpose purpose,
        PaymentStatus status
) {
}
