package com.campus.marketplace.dto;

import java.time.Instant;

/**
 * What anyone may see about a seller. No email, no phone. The rating fields are null / 0 until someone has rated them.
 */
public record SellerProfileResponse(
        Long id,
        String fullName,
        Instant memberSince,
        long activeListings,
        long itemsSold,
        Double averageRating,
        long ratingCount
) {
}
