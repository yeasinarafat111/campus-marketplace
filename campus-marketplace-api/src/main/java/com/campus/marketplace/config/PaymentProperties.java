package com.campus.marketplace.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bound from {@code app.payments.*}. Amounts are never configured directly: the fee for a listing is
 * computed from its price by {@link com.campus.marketplace.service.FeeCalculator} using {@link Fee}.
 *
 * @param successUrl          where Stripe sends the user after paying; may contain {CHECKOUT_SESSION_ID}
 * @param cancelUrl           where Stripe sends the user if they back out
 * @param featureDuration     how long a paid FEATURE_LISTING keeps the listing featured
 * @param reservationDuration how long a paid reservation holds the item before it expires
 */
@Validated
@ConfigurationProperties(prefix = "app.payments")
public record PaymentProperties(
        @NotBlank @Pattern(regexp = "[a-z]{3}", message = "must be a lower-case ISO 4217 code, e.g. usd") String currency,
        @NotNull @Valid Fee fee,
        @NotBlank String successUrl,
        @NotBlank String cancelUrl,
        @NotNull Duration featureDuration,
        @NotNull Duration reservationDuration
) {
    /** Stripe's minimum charge in USD, in cents. */
    public static final long STRIPE_MINIMUM_USD_CENTS = 50;

    public PaymentProperties {
        // Fails startup (instead of every checkout later) if the floor is below what Stripe accepts.
        if ("usd".equals(currency) && fee != null && fee.minimumCents() < STRIPE_MINIMUM_USD_CENTS) {
            throw new IllegalArgumentException("app.payments.fee.minimum-cents must be at least "
                    + STRIPE_MINIMUM_USD_CENTS + " for usd (Stripe's minimum charge), but was " + fee.minimumCents());
        }
    }

    /**
     * The fee policy (decision D1): {@code lowRate} of the price below {@code threshold}, otherwise
     * {@code highRate}, never less than {@code minimumCents}.
     *
     * @param threshold price below this uses {@code lowRate}; at or above uses {@code highRate}
     */
    public record Fee(
            @NotNull @DecimalMin(value = "0.00", inclusive = false) java.math.BigDecimal threshold,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false) java.math.BigDecimal lowRate,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false) java.math.BigDecimal highRate,
            @Min(1) long minimumCents
    ) {
    }
}
