package com.campus.marketplace.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeeCalculatorTest {

    private final FeeCalculator calculator =
            new FeeCalculator(new BigDecimal("20.00"), new BigDecimal("0.03"), new BigDecimal("0.05"), 50);

    /** The table from the playbook (decision D1). */
    @ParameterizedTest(name = "${0} -> {1} cents")
    @CsvSource({
            "12.00,  50",    // 3% = 0.36, below the minimum
            "19.99,  60",    // 3% = 0.5997, rounds up
            "20.00, 100",    // exactly the threshold: 5% = 1.00
            "150.00, 750"})  // 5% = 7.50
    void playbookExamples(String price, long expectedCents) {
        assertThat(calculator.feeCents(new BigDecimal(price))).isEqualTo(expectedCents);
    }

    @Test
    void thresholdIsInclusiveOfTheHighRate() {
        assertThat(calculator.rateFor(new BigDecimal("20.00"))).isEqualByComparingTo("0.05");
        assertThat(calculator.rateFor(new BigDecimal("19.99"))).isEqualByComparingTo("0.03");
        assertThat(calculator.rateFor(new BigDecimal("20"))).isEqualByComparingTo("0.05");
    }

    @Test
    void oneCentBelowTheThresholdUsesTheLowRate() {
        // 3% of 19.99 = 0.5997 -> 0.60, but the 5% rate would give 0.9995 -> 1.00.
        assertThat(calculator.feeCents(new BigDecimal("19.99"))).isEqualTo(60);
        assertThat(calculator.feeCents(new BigDecimal("20.00"))).isEqualTo(100);
    }

    @Test
    void roundsHalfUpToTheCent() {
        // 5% of 30.10 = 1.505 -> 1.51 (half-up; half-even would give 1.50).
        assertThat(calculator.feeCents(new BigDecimal("30.10"))).isEqualTo(151);
        // 5% of 30.30 = 1.515 -> 1.52.
        assertThat(calculator.feeCents(new BigDecimal("30.30"))).isEqualTo(152);
        // 3% of 18.50 = 0.555 -> 0.56.
        assertThat(calculator.feeCents(new BigDecimal("18.50"))).isEqualTo(56);
    }

    @Test
    void minimumAppliesToTinyPrices() {
        assertThat(calculator.feeCents(new BigDecimal("1.00"))).isEqualTo(50);
        assertThat(calculator.feeCents(new BigDecimal("0.01"))).isEqualTo(50);
    }

    @Test
    void largePricesStayExact() {
        // 5% of 99,999,999.99 = 4,999,999.9995 -> 5,000,000.00
        assertThat(calculator.feeCents(new BigDecimal("99999999.99"))).isEqualTo(500_000_000L);
        assertThat(calculator.feeCents(new BigDecimal("12345.67"))).isEqualTo(61728L); // 617.2835 -> 617.28
    }

    @Test
    void rejectsFreeAndInvalidPrices() {
        for (String price : new String[] {"0", "0.00", "-1.00"}) {
            assertThatThrownBy(() -> calculator.feeCents(new BigDecimal(price)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> calculator.feeCents(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.rateFor(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesTheConfiguredPolicy() {
        var custom = new FeeCalculator(new BigDecimal("100"), new BigDecimal("0.10"), new BigDecimal("0.02"), 75);
        assertThat(custom.feeCents(new BigDecimal("50.00"))).isEqualTo(500);   // 10% of 50
        assertThat(custom.feeCents(new BigDecimal("100.00"))).isEqualTo(200);  // 2% of 100
        assertThat(custom.feeCents(new BigDecimal("2.00"))).isEqualTo(75);     // minimum
    }

    @Test
    void toCents_roundsHalfUp() {
        assertThat(FeeCalculator.toCents(new BigDecimal("12.00"))).isEqualTo(1200);
        assertThat(FeeCalculator.toCents(new BigDecimal("0.995"))).isEqualTo(100);
        assertThat(FeeCalculator.toCents(new BigDecimal("1"))).isEqualTo(100);
    }
}
