package com.campus.marketplace.config;

import com.campus.marketplace.entity.PaymentPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bound from {@code app.payments.*}. Amounts are in the currency's smallest unit (e.g. cents) and must
 * be at least Stripe's minimum charge for that currency (USD 0.50).
 *
 * @param successUrl where Stripe sends the user after paying; may contain {CHECKOUT_SESSION_ID}
 * @param cancelUrl       where Stripe sends the user if they back out
 * @param featureDuration how long a paid FEATURE_LISTING keeps the listing featured
 */
@Validated
@ConfigurationProperties(prefix = "app.payments")
public record PaymentProperties(
        @NotBlank @Pattern(regexp = "[a-z]{3}", message = "must be a lower-case ISO 4217 code, e.g. usd") String currency,
        @Positive long featureListingAmount,
        @Positive long reserveItemAmount,
        @NotBlank String successUrl,
        @NotBlank String cancelUrl,
        @NotNull Duration featureDuration
) {
    public long amountFor(PaymentPurpose purpose) {
        return switch (purpose) {
            case FEATURE_LISTING -> featureListingAmount;
            case RESERVE_ITEM -> reserveItemAmount;
        };
    }
}
