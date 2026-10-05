package com.campus.marketplace.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

public class ValidListingPriceValidator implements ConstraintValidator<ValidListingPrice, BigDecimal> {

    private static final BigDecimal MINIMUM_PAID_PRICE = new BigDecimal("1.00");

    @Override
    public boolean isValid(BigDecimal price, ConstraintValidatorContext context) {
        if (price == null || price.signum() < 0) {
            return true; // @NotNull / @DecimalMin report these
        }
        return price.signum() == 0 || price.compareTo(MINIMUM_PAID_PRICE) >= 0;
    }
}
