package com.campus.marketplace.dto;

import java.math.BigDecimal;

/**
 * What the UI shows on the Feature and Reserve buttons. Computed on the server; the client never works
 * out an amount.
 *
 * @param featureCents     fee to feature the listing, in minor units
 * @param reserveCents     deposit to reserve the item, in minor units
 * @param purchaseCents    balance the reserver pays at Buy: price minus the deposit (decision D2). Only
 *                         meaningful for the current reserver
 * @param rate             the nominal fee rate that applied (the minimum may have raised the actual fee)
 * @param minimumCents     the smallest fee ever charged (Stripe's floor)
 * @param basis            the listing price the fee was computed from, e.g. "12.00"
 * @param featureDays      how long a paid feature lasts
 * @param reservationHours how long a paid reservation holds the item
 */
public record FeeQuote(
        long featureCents,
        long reserveCents,
        long purchaseCents,
        String currency,
        BigDecimal rate,
        long minimumCents,
        String basis,
        long featureDays,
        long reservationHours
) {
}
