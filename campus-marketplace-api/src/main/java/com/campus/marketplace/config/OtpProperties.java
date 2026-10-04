package com.campus.marketplace.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bound from {@code app.otp.*}.
 *
 * @param expiration     how long an emailed code stays valid
 * @param maxAttempts    wrong guesses allowed against one code before it is invalidated
 * @param resendCooldown minimum time between two codes for the same user and purpose
 */
@Validated
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        @NotNull Duration expiration,
        @Min(1) int maxAttempts,
        @NotNull Duration resendCooldown
) {
}
