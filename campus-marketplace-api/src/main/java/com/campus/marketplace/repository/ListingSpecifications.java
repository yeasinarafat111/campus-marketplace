package com.campus.marketplace.repository;

import com.campus.marketplace.dto.ListingSearchCriteria;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Composable filters for {@link ListingRepository#findAll(Specification, org.springframework.data.domain.Pageable)}. */
public final class ListingSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private ListingSpecifications() {
    }

    /** Combines every non-null criterion with AND. All-null criteria match every listing. */
    public static Specification<Listing> matching(ListingSearchCriteria criteria) {
        List<Specification<Listing>> specs = new ArrayList<>();
        if (criteria.keyword() != null && !criteria.keyword().isBlank()) {
            specs.add(titleContains(criteria.keyword()));
        }
        if (criteria.category() != null) {
            specs.add(hasCategory(criteria.category()));
        }
        if (criteria.minPrice() != null) {
            specs.add(priceAtLeast(criteria.minPrice()));
        }
        if (criteria.maxPrice() != null) {
            specs.add(priceAtMost(criteria.maxPrice()));
        }
        if (criteria.status() != null) {
            specs.add(hasStatus(criteria.status()));
        }
        return specs.isEmpty() ? Specification.unrestricted() : Specification.allOf(specs);
    }

    /** Case-insensitive substring match. {@code %} and {@code _} in the keyword are matched literally. */
    public static Specification<Listing> titleContains(String keyword) {
        String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern, LIKE_ESCAPE);
    }

    public static Specification<Listing> hasCategory(Category category) {
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    public static Specification<Listing> priceAtLeast(BigDecimal min) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), min);
    }

    public static Specification<Listing> priceAtMost(BigDecimal max) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), max);
    }

    public static Specification<Listing> ownedBy(Long ownerId) {
        return (root, query, cb) -> cb.equal(root.get("owner").get("id"), ownerId);
    }

    /** Excludes listings whose owner has been deactivated by an admin. */
    public static Specification<Listing> ownerActive() {
        return (root, query, cb) -> cb.isTrue(root.get("owner").get("active"));
    }

    public static Specification<Listing> hasStatus(ListingStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
