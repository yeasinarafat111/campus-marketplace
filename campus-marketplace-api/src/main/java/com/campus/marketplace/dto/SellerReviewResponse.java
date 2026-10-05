package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Rating;

import java.time.Instant;

/** A review on a seller's public page. The reviewer is shown by first name only. */
public record SellerReviewResponse(int stars, String comment, String reviewer, Instant createdAt) {

    public static SellerReviewResponse from(Rating r) {
        String full = r.getBuyer().getFullName().trim();
        int space = full.indexOf(' ');
        return new SellerReviewResponse(r.getStars(), r.getComment(), space > 0 ? full.substring(0, space) : full, r.getCreatedAt());
    }
}
