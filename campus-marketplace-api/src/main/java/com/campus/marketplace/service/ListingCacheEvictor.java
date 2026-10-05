package com.campus.marketplace.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.campus.marketplace.config.CacheNames.CATEGORY_COUNTS;
import static com.campus.marketplace.config.CacheNames.FEATURED_LISTINGS;
import static com.campus.marketplace.config.CacheNames.FEE_QUOTE;
import static com.campus.marketplace.config.CacheNames.LISTING_BY_ID;

/**
 * Evicts listing-derived caches for writes that have no method argument to key an annotation on (the Stripe
 * webhook, the expiry sweep, bulk changes). Inside a transaction the eviction waits for the commit, so a
 * rolled-back change can't leave the cache ahead of the database, and nobody re-caches the old row in the
 * gap. Outside a transaction it evicts at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListingCacheEvictor {

    private final CacheManager cacheManager;

    /** One listing changed (a payment applied, a reservation ended): its entry, the featured row, counts and quote. */
    public void evictListing(Long listingId) {
        afterCommit(() -> {
            evict(LISTING_BY_ID, listingId);
            evict(FEE_QUOTE, listingId);
            clear(FEATURED_LISTINGS);
            clear(CATEGORY_COUNTS);
        });
    }

    /** Many listings may have changed (expiry sweep, a seller's name or account changed). */
    public void evictAllListings() {
        afterCommit(() -> {
            clear(LISTING_BY_ID);
            clear(FEE_QUOTE);
            clear(FEATURED_LISTINGS);
            clear(CATEGORY_COUNTS);
        });
    }

    private void afterCommit(Runnable eviction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eviction.run();
                }
            });
        } else {
            eviction.run();
        }
    }

    private void evict(String name, Object key) {
        Cache cache = cacheManager.getCache(name);
        if (cache != null) {
            cache.evict(key);
        }
    }

    private void clear(String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache != null) {
            cache.clear();
        }
    }
}
