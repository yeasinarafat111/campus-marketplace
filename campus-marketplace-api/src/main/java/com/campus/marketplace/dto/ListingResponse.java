package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingResponse(
        Long id,
        String title,
        String description,
        BigDecimal price,
        Category category,
        String imageUrl,
        ListingStatus status,
        Instant createdAt,
        Owner owner,
        boolean featured,
        Instant featuredUntil,
        boolean reserved
) {
    /** Public seller info. The email is deliberately not exposed. */
    public record Owner(Long id, String fullName) {
    }

    /** Call inside a transaction: reads the (lazy) owner. */
    public static ListingResponse from(Listing listing) {
        return new ListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPrice(),
                listing.getCategory(),
                listing.getImageUrl(),
                listing.getStatus(),
                listing.getCreatedAt(),
                new Owner(listing.getOwner().getId(), listing.getOwner().getFullName()),
                listing.isFeatured(Instant.now()),
                listing.getFeaturedUntil(),
                listing.isReserved());
    }
}
