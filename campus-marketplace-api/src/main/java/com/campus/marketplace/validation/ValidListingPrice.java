package com.campus.marketplace.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A listing price is either 0 (a free item) or at least 1.00 (decision D7). Anything between is rejected:
 * the fee minimum is $0.50, so a cheaper item couldn't be reserved and then bought.
 * Null and negative values are left to {@code @NotNull} / {@code @DecimalMin}.
 */
@Documented
@Constraint(validatedBy = ValidListingPriceValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidListingPrice {

    String message() default "Price must be 0 (free) or at least 1.00";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
