package com.campus.marketplace.service;

import com.campus.marketplace.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-05T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final MessageRateLimiter limiter = new MessageRateLimiter(clock);

    @Test
    void allows20AMinute_andRejectsThe21st_withRetryAfter() {
        for (int i = 0; i < 20; i++) {
            limiter.check(1L);
            now.updateAndGet(t -> t.plusSeconds(1));
        }
        assertThatThrownBy(() -> limiter.check(1L))
                .isInstanceOfSatisfying(TooManyRequestsException.class, e ->
                        assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void theWindowSlides_soMessagesAreAllowedAgainAfterAMinute() {
        for (int i = 0; i < 20; i++) {
            limiter.check(1L);
        }
        assertThatThrownBy(() -> limiter.check(1L)).isInstanceOf(TooManyRequestsException.class);

        now.updateAndGet(t -> t.plus(Duration.ofSeconds(61)));

        assertThatCode(() -> limiter.check(1L)).doesNotThrowAnyException();
    }

    @Test
    void usersAreCountedSeparately() {
        for (int i = 0; i < 20; i++) {
            limiter.check(1L);
        }
        assertThatCode(() -> limiter.check(2L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(1L)).isInstanceOf(TooManyRequestsException.class);
    }
}
