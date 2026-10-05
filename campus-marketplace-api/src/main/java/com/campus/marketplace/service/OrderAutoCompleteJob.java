package com.campus.marketplace.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Runs the order auto-complete on a schedule (its own bean so the transaction proxy applies). */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderAutoCompleteJob {

    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${ORDER_SWEEP_INTERVAL:15m}", initialDelayString = "${ORDER_SWEEP_INITIAL_DELAY:2m}")
    public void sweep() {
        try {
            orderService.autoCompleteDue(Instant.now());
        } catch (RuntimeException e) {
            log.error("Order auto-complete sweep failed", e);
        }
    }
}
