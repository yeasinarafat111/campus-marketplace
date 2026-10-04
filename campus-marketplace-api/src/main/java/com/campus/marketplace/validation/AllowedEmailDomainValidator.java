package com.campus.marketplace.validation;

import com.campus.marketplace.config.RegistrationProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

import java.util.Locale;

/**
 * Spring creates this validator (via Spring's ConstraintValidatorFactory), so the configured domain is
 * injected rather than hard-coded.
 */
public class AllowedEmailDomainValidator implements ConstraintValidator<AllowedEmailDomain, String> {

    private final String requiredSuffix;
    private final String domain;

    public AllowedEmailDomainValidator(RegistrationProperties properties) {
        this.domain = properties.allowedEmailDomain();
        this.requiredSuffix = "@" + domain;
    }

    @Override
    public boolean isValid(String email, ConstraintValidatorContext context) {
        if (email == null || email.isBlank()) {
            return true; // @NotBlank reports this
        }
        if (!looksLikeEmail(email)) {
            return true; // @Email reports this, so a malformed address gets one clear message, not two
        }
        // Passed as a message parameter, not concatenated into the template, so it is never interpreted as EL.
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("domain", domain);
        return email.trim().toLowerCase(Locale.ROOT).endsWith(requiredSuffix);
    }

    private static boolean looksLikeEmail(String email) {
        String value = email.trim();
        int at = value.indexOf('@');
        return at > 0 && at == value.lastIndexOf('@') && at < value.length() - 1
                && value.chars().noneMatch(Character::isWhitespace);
    }
}
