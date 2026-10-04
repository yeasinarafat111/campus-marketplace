package com.campus.marketplace.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated email must belong to the configured registration domain
 * ({@code app.registration.allowed-email-domain}, env var {@code ALLOWED_EMAIL_DOMAIN}).
 * <p>
 * The domain must match exactly (case-insensitive): subdomains and look-alikes such as
 * {@code cse.seu.edu.bd} or {@code seu.edu.bd.example.com} are rejected. Null, blank and values not shaped
 * like {@code local@domain} are left to {@code @NotBlank} / {@code @Email}, so combine it with those.
 * The message may use {@code {domain}}.
 */
@Documented
@Constraint(validatedBy = AllowedEmailDomainValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedEmailDomain {

    String message() default "Only @{domain} email addresses can register";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
