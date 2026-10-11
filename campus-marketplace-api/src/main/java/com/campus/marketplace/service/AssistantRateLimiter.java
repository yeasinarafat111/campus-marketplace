package com.campus.marketplace.service;

import com.campus.marketplace.config.AssistantProperties;
import com.campus.marketplace.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional sliding-window limits on calls to Gemini: per user per hour, and for the whole service per 24 hours. A
 * limit of 0 means unlimited, which is the default: for the showcase nothing on our side refuses an assistant
 * request, and Gemini's own quota errors become a friendly answer instead (see AssistantService). In memory and per
 * instance, which is exact while the service runs as one instance (it must, for chat). Cached answers never count.
 */
@Component
public class AssistantRateLimiter {

    static final Duration USER_WINDOW = Duration.ofHours(1);
    static final Duration GLOBAL_WINDOW = Duration.ofDays(1);

    private final Clock clock;
    private final AssistantProperties properties;
    private final Map<Long, Deque<Instant>> perUser = new ConcurrentHashMap<>();
    private final Deque<Instant> global = new ArrayDeque<>();

    public AssistantRateLimiter(Clock clock, AssistantProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** Forgets every counter. For tests. */
    public void reset() {
        perUser.clear();
        synchronized (global) {
            global.clear();
        }
    }

    /**
     * Counts one call for the user, against both limits, or refuses without counting.
     *
     * @throws TooManyRequestsException with how long to wait
     */
    public void acquire(Long userId) {
        int userLimit = properties.perUserPerHour();
        int globalLimit = properties.globalPerDay();
        if (userLimit == 0 && globalLimit == 0) {
            return;
        }
        Instant now = clock.instant();
        Deque<Instant> mine = perUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (mine) {
            prune(mine, now, USER_WINDOW);
            if (userLimit > 0 && mine.size() >= userLimit) {
                long wait = retryAfter(mine.peekFirst(), now, USER_WINDOW);
                throw new TooManyRequestsException("You've used the AI assistant " + userLimit
                        + " times in the last hour. Try again in " + human(wait) + ".", wait);
            }
            synchronized (global) {
                prune(global, now, GLOBAL_WINDOW);
                if (globalLimit > 0 && global.size() >= globalLimit) {
                    long wait = retryAfter(global.peekFirst(), now, GLOBAL_WINDOW);
                    throw new TooManyRequestsException("The AI assistant has reached its daily limit. Try again in "
                            + human(wait) + ".", wait);
                }
                global.addLast(now);
            }
            mine.addLast(now);
        }
        if (perUser.size() > 10_000) {
            perUser.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || !e.getValue().peekLast().isAfter(now.minus(USER_WINDOW));
                }
            });
        }
    }

    private static void prune(Deque<Instant> window, Instant now, Duration length) {
        Instant cutoff = now.minus(length);
        while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
            window.pollFirst();
        }
    }

    private static long retryAfter(Instant oldest, Instant now, Duration window) {
        return Math.max(1, Duration.between(now, oldest.plus(window)).toSeconds() + 1);
    }

    /** "45 seconds", "12 minutes", "3 hours". */
    static String human(long seconds) {
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = (seconds + 59) / 60;
        if (minutes < 120) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = (minutes + 59) / 60;
        return hours + " hours";
    }
}
