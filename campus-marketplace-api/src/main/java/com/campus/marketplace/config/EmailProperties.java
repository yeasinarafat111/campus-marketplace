package com.campus.marketplace.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code app.mail.*}. SMTP connection settings live under {@code spring.mail.*}.
 *
 * @param from sender address; many shared hosts reject mail whose From differs from the SMTP login
 */
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record EmailProperties(@NotBlank @Email String from) {
}
