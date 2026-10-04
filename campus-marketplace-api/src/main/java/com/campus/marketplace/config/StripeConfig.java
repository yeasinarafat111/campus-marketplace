package com.campus.marketplace.config;

import com.stripe.StripeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class StripeConfig {

    @Bean
    public StripeClient stripeClient(StripeProperties properties) {
        if (!properties.isConfigured()) {
            log.warn("STRIPE_SECRET_KEY is not set; checkout sessions will be rejected with 503");
        } else {
            log.info("Stripe configured in {} mode", properties.isLiveMode() ? "LIVE" : "test");
        }
        if (!properties.hasWebhookSecret()) {
            log.warn("STRIPE_WEBHOOK_SECRET is not set; Stripe webhooks will be rejected with 503 and payments stay PENDING");
        }
        // StripeClient requires a non-null key; the gateway refuses to call Stripe when unconfigured.
        return new StripeClient(properties.isConfigured() ? properties.secretKey() : "sk_unconfigured");
    }
}
