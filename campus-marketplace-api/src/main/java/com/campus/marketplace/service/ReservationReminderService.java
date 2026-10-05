package com.campus.marketplace.service;

import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.NotificationType;
import com.campus.marketplace.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Tells buyers their reservation is about to end. Each reservation is reminded once (the notice carries a dedupe key). */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationReminderService {

    static final Duration WARN_BEFORE = Duration.ofHours(6);

    private final ListingRepository listingRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    /** Returns how many reminders were created. */
    @Transactional
    public int sendDueReminders() {
        Instant now = Instant.now(clock);
        int sent = 0;
        for (Listing listing : listingRepository.findReservationsExpiringBetween(now, now.plus(WARN_BEFORE))) {
            // The expiry time is part of the key: a later reservation of the same item is a new reminder.
            String key = "reservation-expiring:" + listing.getId() + ":" + listing.getReservationExpiresAt().getEpochSecond();
            if (notificationService.notifyOnce(listing.getReservedBy().getId(), NotificationType.RESERVATION_EXPIRING,
                    "Your reservation ends soon",
                    "Your reservation of \"" + listing.getTitle() + "\" ends in under 6 hours. Buy it now or the deposit is lost.",
                    "/listings/" + listing.getId(), key)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Sent {} reservation-expiring reminder(s)", sent);
        }
        return sent;
    }
}
