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

    record CheckoutSession(String id, String url) {
    }
}
