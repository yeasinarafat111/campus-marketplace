package com.campus.marketplace.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Hourly: deletes listings that have waited more than 24 hours for their posting fee (its own bean so the transaction applies). */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingListingCleanupJob {

    private final ListingService listingService;

    @Scheduled(fixedDelayString = "${PENDING_SWEEP_INTERVAL:1h}", initialDelayString = "${PENDING_SWEEP_INITIAL_DELAY:3m}")
    public void sweep() {
        try {
            listingService.deleteStalePending(Instant.now());
        } catch (RuntimeException e) {
            log.error("Pending-listing cleanup failed", e);
        }
    }
}
