package com.campus.marketplace.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Locale;

/**
 * Bound from {@code app.registration.*}.
 *
 * @param allowedEmailDomain the only email domain allowed to sign up, e.g. {@code seu.edu.bd}.
 *                           A leading "@" is tolerated and the value is lower-cased.
 */
@Validated
@ConfigurationProperties(prefix = "app.registration")
public record RegistrationProperties(@NotBlank String allowedEmailDomain) {

    public RegistrationProperties {
        if (allowedEmailDomain != null) {
            allowedEmailDomain = allowedEmailDomain.trim().toLowerCase(Locale.ROOT);
            if (allowedEmailDomain.startsWith("@")) {
                allowedEmailDomain = allowedEmailDomain.substring(1);
            }
        }
    }
}
