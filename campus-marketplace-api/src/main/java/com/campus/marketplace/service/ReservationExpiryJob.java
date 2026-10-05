package com.campus.marketplace.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the reservation sweep on a schedule. A separate bean on purpose: the sweep's bulk UPDATEs need the
 * transaction that {@link ReservationService#releaseExpired()} opens, and a method calling another method on
 * its own class would bypass that proxy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpiryJob {

    private final ReservationService reservationService;

    @Scheduled(fixedDelayString = "${RESERVATION_SWEEP_INTERVAL:5m}", initialDelayString = "${RESERVATION_SWEEP_INITIAL_DELAY:1m}")
    public void sweep() {
        try {
            reservationService.releaseExpired();
        } catch (RuntimeException e) {
            // Never let one failed run stop the schedule; the next run retries.
            log.error("Reservation expiry sweep failed", e);
        }
    }
}
