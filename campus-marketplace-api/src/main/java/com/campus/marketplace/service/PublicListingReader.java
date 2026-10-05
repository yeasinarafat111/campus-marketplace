package com.campus.marketplace.service;

import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.ListingSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.campus.marketplace.config.CacheNames.CATEGORY_COUNTS;
import static com.campus.marketplace.config.CacheNames.FEATURED_LISTINGS;
import static com.campus.marketplace.config.CacheNames.LISTING_BY_ID;

/**
 * The cached, viewer-independent reads. A separate bean on purpose: a {@code @Cacheable} method called from
 * the same class would bypass the cache proxy (see {@code CacheConfig}). Everything returned is the public
 * view, so one cached copy is correct for every caller; {@link ListingService#get} adds what depends on who is
 * asking. Writes evict these caches (see {@code ListingCacheEvictor} and the annotations on the writers).
 */
@Service
@RequiredArgsConstructor
public class PublicListingReader {

    static final int FEATURED_ROW_SIZE = 10;

    private final ListingRepository listingRepository;

    /** The public view of a listing whose owner is active. A missing or hidden listing is a 404 (not cached). */
    @Cacheable(cacheNames = LISTING_BY_ID, key = "#id")
    @Transactional(readOnly = true)
    public ListingResponse publicView(Long id) {
        return listingRepository.findWithOwnerById(id)
                .filter(l -> l.getOwner().isActive())
                // A listing waiting for its posting fee is not public. Not cached either (a 404 isn't).
                .filter(l -> l.getStatus() != com.campus.marketplace.entity.ListingStatus.PENDING_PAYMENT)
                .map(ListingResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + id + " not found"));
    }

    /** The newest currently featured listings, for the front page's Featured row. */
    @Cacheable(cacheNames = FEATURED_LISTINGS, key = "'home'")
    @Transactional(readOnly = true)
    public List<ListingResponse> featured() {
        var spec = ListingSpecifications.matching(new com.campus.marketplace.dto.ListingSearchCriteria(
                        null, null, null, null, ListingStatus.ACTIVE))
                .and(ListingSpecifications.ownerActive())
                .and(ListingSpecifications.featuredAt(Instant.now()));
        var pageable = PageRequest.of(0, FEATURED_ROW_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return listingRepository.findAll(spec, pageable).stream().map(ListingResponse::from).toList();
    }

    /** How many active listings each category has, for the category chips. Every category is present. */
    @Cacheable(cacheNames = CATEGORY_COUNTS, key = "'all'")
    @Transactional(readOnly = true)
    public Map<Category, Long> categoryCounts() {
        Map<Category, Long> counts = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            counts.put(c, 0L);
        }
        for (Object[] row : listingRepository.countActiveByCategory()) {
            counts.put((Category) row[0], (Long) row[1]);
        }
        return counts;
    }
}
