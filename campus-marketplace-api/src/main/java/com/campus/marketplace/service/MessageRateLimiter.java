package com.campus.marketplace.service;

import com.campus.marketplace.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@value #MAX_MESSAGES} messages per user per minute (a sliding window), in memory. Per instance, which
 * is exact while the service runs as one instance (it must, for chat) and a generous bound otherwise.
 */
@Component
public class MessageRateLimiter {

    static final int MAX_MESSAGES = 20;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<Long, Deque<Instant>> sent = new ConcurrentHashMap<>();

    @Autowired
    public MessageRateLimiter() {
        this(Clock.systemUTC());
    }

    MessageRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records one message for the user.
     *
     * @throws TooManyRequestsException if they have already sent {@value #MAX_MESSAGES} in the last minute
     */
    public void check(Long userId) {
        Instant now = clock.instant();
        Deque<Instant> window = sent.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (window) {
            Instant cutoff = now.minus(WINDOW);
            while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= MAX_MESSAGES) {
                long retryAfter = Math.max(1, Duration.between(now, window.peekFirst().plus(WINDOW)).toSeconds() + 1);
                throw new TooManyRequestsException("You're sending messages too quickly. Please wait a moment.", retryAfter);
            }
            window.addLast(now);
        }
        if (sent.size() > 10_000) {
            // Keeps the map from growing without bound: drop users whose window has emptied.
            sent.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || !e.getValue().peekLast().isAfter(now.minus(WINDOW));
                }
            });
        }
    }
}
