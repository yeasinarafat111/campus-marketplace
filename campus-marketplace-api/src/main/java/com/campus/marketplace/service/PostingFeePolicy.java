package com.campus.marketplace.service;

import com.campus.marketplace.config.PaymentProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * When a new listing needs a posting fee: the feature is on and the price is strictly above the threshold
 * ($5.00 by default). The amount itself is the usual {@link FeeCalculator} fee.
 */
@Component
public class PostingFeePolicy {

    private final boolean enabled;
    private final BigDecimal threshold;

    public PostingFeePolicy(PaymentProperties properties) {
        this.enabled = properties.postingFee().enabled();
        this.threshold = properties.postingFee().threshold();
    }

    public boolean enabled() {
        return enabled;
    }

    /** True for a price strictly above the threshold (so $5.00 is free and $5.01 pays) while the feature is on. */
    public boolean applies(BigDecimal price) {
        return enabled && price != null && price.compareTo(threshold) > 0;
    }

    /** Minor units as dollars and cents, for messages: 1234 becomes "$12.34". */
    public static String format(long cents) {
        return "$%d.%02d".formatted(cents / 100, cents % 100);
    }

    public long thresholdCents() {
        return FeeCalculator.toCents(threshold);
    }

    /**
     * What a listing's posting covers once published: the price the fee was paid on, or the threshold when no fee
     * was due. Null when posting fees are switched off, so nothing is restricted.
     */
    public Long coveredCentsWithoutFee() {
        return enabled ? thresholdCents() : null;
    }
}
