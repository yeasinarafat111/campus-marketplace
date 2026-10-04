package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.ListingStatus;

import java.math.BigDecimal;

/**
 * Optional filters for listing search; any {@code null} (or blank keyword) field is ignored.
 *
 * @param keyword  case-insensitive substring of the title
 * @param minPrice inclusive lower bound
 * @param maxPrice inclusive upper bound
 */
public record ListingSearchCriteria(
        String keyword,
        Category category,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        ListingStatus status
) {
}
