package com.campus.marketplace.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the reminder sweep on a schedule. A separate bean so the service's transaction proxy applies. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReminderJob {

    private final ReservationReminderService reminderService;

    @Scheduled(fixedDelayString = "${RESERVATION_REMINDER_INTERVAL:15m}", initialDelayString = "${RESERVATION_REMINDER_INITIAL_DELAY:2m}")
    public void run() {
        try {
            reminderService.sendDueReminders();
        } catch (RuntimeException e) {
            log.error("Reservation reminder sweep failed", e);
        }
    }
}
