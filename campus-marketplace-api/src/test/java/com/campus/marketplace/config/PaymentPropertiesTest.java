package com.campus.marketplace.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentPropertiesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static PaymentProperties.Fee fee(String low, String high, long minimumCents) {
        return new PaymentProperties.Fee(new BigDecimal("20.00"), new BigDecimal(low), new BigDecimal(high), minimumCents);
    }

    private static PaymentProperties props(String currency, PaymentProperties.Fee fee) {
        return new PaymentProperties(currency, fee, "http://x/success", "http://x/cancel",
                Duration.ofDays(7), Duration.ofHours(48));
    }

    @Test
    void defaultsAreValid() {
        var properties = props("usd", fee("0.03", "0.05", 50));
        assertThat(VALIDATOR.validate(properties)).isEmpty();
        assertThat(VALIDATOR.validate(properties.fee())).isEmpty();
    }

    @Test
    void ratesMustBeStrictlyBetweenZeroAndOne() {
        for (String bad : new String[] {"0", "1", "1.5", "-0.01"}) {
            assertThat(VALIDATOR.validate(fee(bad, "0.05", 50))).as("low rate " + bad).isNotEmpty();
            assertThat(VALIDATOR.validate(fee("0.03", bad, 50))).as("high rate " + bad).isNotEmpty();
        }
        assertThat(VALIDATOR.validate(fee("0.0001", "0.9999", 50))).isEmpty();
    }

    @Test
    void usdMinimumBelowStripesFloorFailsStartup() {
        assertThatThrownBy(() -> props("usd", fee("0.03", "0.05", 49)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 50");
        assertThat(props("usd", fee("0.03", "0.05", 50)).fee().minimumCents()).isEqualTo(50);
        assertThat(props("usd", fee("0.03", "0.05", 100)).fee().minimumCents()).isEqualTo(100);
    }

    @Test
    void minimumMustBePositive() {
        assertThat(VALIDATOR.validate(fee("0.03", "0.05", 0))).isNotEmpty();
    }

    @Test
    void thresholdMustBePositive() {
        var bad = new PaymentProperties.Fee(BigDecimal.ZERO, new BigDecimal("0.03"), new BigDecimal("0.05"), 50);
        assertThat(VALIDATOR.validate(bad)).isNotEmpty();
    }
}
