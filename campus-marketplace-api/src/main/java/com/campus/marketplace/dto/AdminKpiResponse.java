package com.campus.marketplace.dto;

import java.time.LocalDate;

/**
 * The numbers at the top of the admin dashboard. {@code netRevenueThisWeekCents} runs from Monday {@code weekFrom}
 * to today, before Stripe's processing fees.
 */
public record AdminKpiResponse(
        long users,
        long activeListings,
        long orders,
        long netRevenueThisWeekCents,
        String currency,
        LocalDate weekFrom,
        long openReports,
        long openOrderProblems
) {
}
