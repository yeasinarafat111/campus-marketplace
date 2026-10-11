package com.campus.marketplace.service;

import com.campus.marketplace.config.AssistantProperties;
import com.campus.marketplace.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantRateLimiterTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-12T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static AssistantProperties props(int perUser, int global) {
        return new AssistantProperties(true, AssistantProperties.Gateway.FAKE, null, "m", "http://x", false, false, "low",
                perUser, global, Duration.ofSeconds(2), 1000, new BigDecimal("120"), List.of());
    }

    @Test
    void zeroMeansUnlimited() {
        AssistantRateLimiter limiter = new AssistantRateLimiter(new MutableClock(), props(0, 0));
        assertThatCode(() -> {
            for (int i = 0; i < 1000; i++) {
                limiter.acquire(1L);
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void perUserLimit_saysHowLongToWait_andSlides() {
        MutableClock clock = new MutableClock();
        AssistantRateLimiter limiter = new AssistantRateLimiter(clock, props(2, 0));
        limiter.acquire(1L);
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        limiter.acquire(1L);
        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Try again in 51 minutes") // 50 min 1 s, rounded up
                .satisfies(e -> assertThat(((TooManyRequestsException) e).getRetryAfterSeconds()).isEqualTo(50 * 60 + 1));
        limiter.acquire(2L); // other users unaffected
        clock.now = clock.now.plus(Duration.ofMinutes(51));
        assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
    }

    @Test
    void globalLimit_countsEveryone() {
        MutableClock clock = new MutableClock();
        AssistantRateLimiter limiter = new AssistantRateLimiter(clock, props(0, 3));
        limiter.acquire(1L);
        limiter.acquire(2L);
        limiter.acquire(3L);
        assertThatThrownBy(() -> limiter.acquire(4L))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("daily limit");
        clock.now = clock.now.plus(Duration.ofDays(1)).plusSeconds(1);
        assertThatCode(() -> limiter.acquire(4L)).doesNotThrowAnyException();
    }

    @Test
    void refusedCallsDontCount() {
        MutableClock clock = new MutableClock();
        AssistantRateLimiter limiter = new AssistantRateLimiter(clock, props(1, 2));
        limiter.acquire(1L);
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.acquire(2L)).doesNotThrowAnyException(); // the refusal didn't use a global slot
    }

    @Test
    void humanDurations() {
        assertThat(AssistantRateLimiter.human(1)).isEqualTo("1 second");
        assertThat(AssistantRateLimiter.human(45)).isEqualTo("45 seconds");
        assertThat(AssistantRateLimiter.human(61)).isEqualTo("2 minutes");
        assertThat(AssistantRateLimiter.human(3 * 3600)).isEqualTo("3 hours");
    }
}
