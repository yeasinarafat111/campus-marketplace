package com.campus.marketplace.repository;

import com.campus.marketplace.dto.ListingSearchCriteria;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.ItemCondition;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
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

    public static Specification<Listing> hasCondition(ItemCondition condition) {
        return (root, query, cb) -> cb.equal(root.get("itemCondition"), condition);
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

    /** Listings whose paid featured period hasn't ended yet. */
    public static Specification<Listing> featuredAt(Instant now) {
        return (root, query, cb) -> cb.greaterThan(root.get("featuredUntil"), now);
    }

    /** True: only reserved listings. False: only unreserved ones. */
    public static Specification<Listing> reserved(boolean reserved) {
        return (root, query, cb) -> reserved
                ? cb.isNotNull(root.get("reservedBy"))
                : cb.isNull(root.get("reservedBy"));
    }

    /**
     * Default browse order: currently featured listings first, then newest. Not expressible as a
     * {@code Sort} (it needs a CASE on the clock), so it is applied as the query's ORDER BY. Use it with an
     * <em>unsorted</em> Pageable: Spring Data replaces the spec's ordering whenever the Pageable has a sort.
     * The count query must not be ordered, so it is skipped for non-entity results.
     */
    public static Specification<Listing> featuredFirstThenNewest(Instant now) {
        return (root, query, cb) -> {
            if (query != null && Listing.class.equals(query.getResultType())) {
                query.orderBy(
                        cb.desc(cb.selectCase().when(cb.greaterThan(root.<Instant>get("featuredUntil"), now), 1).otherwise(0)),
                        cb.desc(root.get("createdAt")),
                        cb.desc(root.get("id")));
            }
            return cb.conjunction();
        };
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
