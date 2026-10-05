package com.campus.marketplace.dto;

import com.campus.marketplace.entity.LedgerType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Platform revenue for a date range, before Stripe's processing fees. Money is in minor units.
 *
 * @param totals              revenue per source over the range (refunds already subtracted); every source is present
 * @param netCents            the sum of {@code totals}
 * @param series              the same figures per day, week or month, with empty periods included as zeros
 * @param owedToSellersCents  purchase money the platform holds for sellers who haven't been paid out yet (a balance
 *                            as of now, not part of the range): the whole price of an online sale, only the deposit of
 *                            a cash sale. Not revenue
 * @param depositsHeldCents   deposits of reservations that are still running (as of now). Not revenue unless forfeited
 */
public record RevenueReport(
        LocalDate from,
        LocalDate to,
        String groupBy,
        String currency,
        Map<LedgerType, Long> totals,
        long netCents,
        List<Bucket> series,
        long owedToSellersCents,
        long depositsHeldCents
) {
    /** One period of the series; {@code bucket} is its first day (ISO date). */
    public record Bucket(String bucket, Map<LedgerType, Long> byType, long netCents) {
    }
}
