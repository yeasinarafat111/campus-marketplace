package com.campus.marketplace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.stripe.*}. Both values are optional so the app starts without them: checkout
 * returns 503 without the secret key, and the webhook returns 503 (Stripe retries) without the webhook
 * secret. Events are never accepted unsigned.
 *
 * @param secretKey     env var STRIPE_SECRET_KEY (sk_test_… / sk_live_…)
 * @param webhookSecret env var STRIPE_WEBHOOK_SECRET (whsec_…), the endpoint's signing secret
 */
@ConfigurationProperties(prefix = "app.stripe")
public record StripeProperties(String secretKey, String webhookSecret) {

    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    public boolean isLiveMode() {
        return isConfigured() && secretKey.startsWith("sk_live_");
    }
}
