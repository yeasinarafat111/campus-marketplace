package com.campus.marketplace.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated value must be a Bangladeshi mobile number: {@code 01XXXXXXXXX}, {@code 8801…} or
 * {@code +8801…} (spaces and dashes allowed), where the operator digit is 3-9. See {@link PhoneNumbers}.
 * <p>
 * Null and blank values are left to {@code @NotBlank}, so combine it with that when the field is required.
 * The validator accepts the raw input; DTOs should store {@code PhoneNumbers.normalize(...)}.
 */
@Documented
@Constraint(validatedBy = BangladeshiPhoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BangladeshiPhone {

    String message() default "Phone number must be a valid Bangladeshi mobile number, like 01XXXXXXXXX";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
