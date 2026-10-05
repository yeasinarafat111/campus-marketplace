package com.campus.marketplace.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    private final BangladeshiPhoneValidator validator = new BangladeshiPhoneValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "01712345678", "8801712345678", "+8801712345678",
            "017 1234 5678", "017-1234-5678", "+880 1712-345678", " 01312345678 ", "01999999999"})
    void normalize_acceptedFormats_giveE164(String raw) {
        assertThat(PhoneNumbers.isValid(raw)).isTrue();
        assertThat(PhoneNumbers.normalize(raw)).matches("^\\+8801[3-9]\\d{8}$");
    }

    @Test
    void normalize_givesSameResultForAllThreeInputForms() {
        assertThat(PhoneNumbers.normalize("01712345678")).isEqualTo("+8801712345678");
        assertThat(PhoneNumbers.normalize("8801712345678")).isEqualTo("+8801712345678");
        assertThat(PhoneNumbers.normalize("+8801712345678")).isEqualTo("+8801712345678");
        assertThat(PhoneNumbers.normalize("017-1234 5678")).isEqualTo("+8801712345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0171234567",        // too short
            "017123456789",      // too long
            "01212345678",       // operator digit 2 is not a mobile prefix
            "01012345678",       // operator digit 0
            "+8801212345678",
            "+8802712345678",    // not a mobile number
            "+911712345678",     // other country
            "1712345678",        // no prefix
            "abc", "0171234567a", "+88017123456789", "++8801712345678"})
    void invalidNumbers_areRejected(String raw) {
        assertThat(PhoneNumbers.isValid(raw)).isFalse();
    }

    @Test
    void normalize_nullStaysNull() {
        assertThat(PhoneNumbers.normalize(null)).isNull();
        assertThat(PhoneNumbers.isValid(null)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void validator_leavesNullAndBlankToNotBlank(String value) {
        assertThat(validator.isValid(value, (ConstraintValidatorContext) null)).isTrue();
    }

    @Test
    void validator_checksFormat() {
        assertThat(validator.isValid("01712345678", null)).isTrue();
        assertThat(validator.isValid("12345", null)).isFalse();
    }
}
