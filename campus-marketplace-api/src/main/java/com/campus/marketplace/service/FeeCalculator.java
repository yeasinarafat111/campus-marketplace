package com.campus.marketplace.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the platform fee for a listing price (decision D1). Pure: no Spring, no clock, no I/O.
 * <p>
 * The fee is {@code lowRate} of the price below the threshold and {@code highRate} at or above it, rounded
 * half-up to the cent, and never less than the minimum (Stripe's floor). Money maths is in
 * {@link BigDecimal}; the result is integer minor units, which is what Stripe takes.
 */
public final class FeeCalculator {

    private final BigDecimal threshold;
    private final BigDecimal lowRate;
    private final BigDecimal highRate;
    private final long minimumCents;

    public FeeCalculator(BigDecimal threshold, BigDecimal lowRate, BigDecimal highRate, long minimumCents) {
        this.threshold = threshold;
        this.lowRate = lowRate;
        this.highRate = highRate;
        this.minimumCents = minimumCents;
    }

    /** The nominal rate for a price: the low rate strictly below the threshold, the high rate from it up. */
    public BigDecimal rateFor(BigDecimal price) {
        requirePositive(price);
        return price.compareTo(threshold) < 0 ? lowRate : highRate;
    }

    /**
     * @throws IllegalArgumentException if the price is null, zero or negative (free items have no paid flows)
     */
    public long feeCents(BigDecimal price) {
        BigDecimal fee = price.multiply(rateFor(price)).setScale(2, RoundingMode.HALF_UP);
        return Math.max(fee.movePointRight(2).longValueExact(), minimumCents);
    }

    /** A price in minor units, rounded half-up to the cent. */
    public static long toCents(BigDecimal price) {
        return price.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static void requirePositive(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("Fees apply to paid items only; price must be greater than zero");
        }
    }
}
