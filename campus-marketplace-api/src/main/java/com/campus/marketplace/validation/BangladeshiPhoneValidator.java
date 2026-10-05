package com.campus.marketplace.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BangladeshiPhoneValidator implements ConstraintValidator<BangladeshiPhone, String> {

    @Override
    public boolean isValid(String phone, ConstraintValidatorContext context) {
        if (phone == null || phone.isBlank()) {
            return true; // @NotBlank reports this
        }
        return PhoneNumbers.isValid(phone);
    }
}
