package com.campus.marketplace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.rate-limit.*}.
 *
 * @param enabled         switch for the auth rate limits (the test profile turns it off except in its own tests)
 * @param trustedProxies  how many proxies sit in front of the app and append to {@code X-Forwarded-For}. The client
 *                        address is the entry that many places from the right: a client can put anything it likes at
 *                        the left of that header, but not at the right. Cloud Run adds one, so the default is 1
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(boolean enabled, int trustedProxies) {
    public RateLimitProperties {
        if (trustedProxies < 0) {
            throw new IllegalArgumentException("app.rate-limit.trusted-proxies must not be negative");
        }
    }
}
