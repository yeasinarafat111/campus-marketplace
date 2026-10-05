package com.campus.marketplace.service;

import java.util.Map;

/** Creates hosted checkout pages. Implemented by {@link StripeCheckoutGateway}. */
public interface CheckoutGateway {

    /**
     * @throws com.campus.marketplace.exception.PaymentProviderException if the provider fails or isn't configured
     */
    CheckoutSession createCheckoutSession(CheckoutRequest request);

    /**
     * @param amount   smallest currency unit (e.g. cents)
     * @param metadata stored on the session and its PaymentIntent, for matching webhooks back to our records
     */
    record CheckoutRequest(long amount, String currency, String productName, String customerEmail,
                           Map<String, String> metadata) {
    }

    /**
     * Returns a payment's money to the payer. Idempotent for a given {@code idempotencyKey}, and a payment
     * that is already fully refunded counts as success, so a retry after a half-finished attempt is safe.
     *
     * @throws com.campus.marketplace.exception.PaymentProviderException if the provider fails or isn't configured
     */
    void refund(String paymentIntentId, String idempotencyKey);

    /**
     * The PaymentIntent behind a checkout session, for payments recorded before the id was stored.
     *
     * @throws com.campus.marketplace.exception.PaymentProviderException if the provider fails or isn't configured
     */
    String paymentIntentIdOf(String checkoutSessionId);

    record CheckoutSession(String id, String url) {
    }
}
