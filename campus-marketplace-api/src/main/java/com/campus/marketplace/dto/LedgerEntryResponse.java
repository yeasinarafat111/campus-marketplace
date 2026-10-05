package com.campus.marketplace.dto;

import com.campus.marketplace.entity.LedgerEntry;
import com.campus.marketplace.entity.LedgerType;

import java.time.Instant;

/** One revenue ledger line for the admin history table. {@code amountCents} is negative for a refund. */
public record LedgerEntryResponse(
        Long id,
        LedgerType type,
        long amountCents,
        String currency,
        Instant occurredAt,
        Long paymentId,
        ListingRef listing,
        UserRef user
) {
    /** The title is the copy taken when the entry was written, so it survives the listing being deleted. */
    public record ListingRef(Long id, String title) {
    }

    public record UserRef(Long id, String fullName, String email) {
    }

    public static LedgerEntryResponse of(LedgerEntry e, UserRef user) {
        return new LedgerEntryResponse(e.getId(), e.getType(), e.getAmountCents(), e.getCurrency(), e.getOccurredAt(),
                e.getPaymentId(), e.getListingId() == null && e.getListingTitle() == null ? null
                        : new ListingRef(e.getListingId(), e.getListingTitle()), user);
    }
}
