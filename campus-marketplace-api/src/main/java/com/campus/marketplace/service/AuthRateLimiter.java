package com.campus.marketplace.service;

import com.campus.marketplace.config.RateLimitProperties;
import com.campus.marketplace.exception.TooManyRequestsException;
import com.campus.marketplace.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory limits on the account endpoints, to slow down guessing and mail bombing. Per client address (every
 * attempt counts) and per email (login counts only failures). Sliding windows; exceeding a limit is a 429 with a
 * Retry-After. Per instance, which is exact while the service runs as one instance and a generous bound otherwise.
 */
@Component
public class AuthRateLimiter {

    public enum Action {
        LOGIN(10, Duration.ofMinutes(1)),
        SIGNUP(5, Duration.ofMinutes(1)),
        FORGOT_PASSWORD(5, Duration.ofMinutes(1)),
        VERIFY_OTP(10, Duration.ofMinutes(1)),
        RESEND_OTP(5, Duration.ofMinutes(1));

        final int perIp;
        final Duration ipWindow;

        Action(int perIp, Duration ipWindow) {
            this.perIp = perIp;
            this.ipWindow = ipWindow;
        }
    }

    /** Failed logins allowed per email before it is locked out for the rest of the window. */
    static final int MAX_FAILED_LOGINS_PER_EMAIL = 5;
    /** Requests that send mail or check a code, per email. */
    static final int MAX_REQUESTS_PER_EMAIL = 5;
    static final int MAX_CODE_ATTEMPTS_PER_EMAIL = 10;
    static final Duration EMAIL_WINDOW = Duration.ofMinutes(15);
    private static final int CLEANUP_THRESHOLD = 20_000;

    private final Clock clock;
    private final RateLimitProperties properties;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    public AuthRateLimiter(Clock clock, RateLimitProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** Forgets every counter. For tests, which share one limiter across cases. */
    public void reset() {
        windows.clear();
    }

    /** The caller's address, as this limiter sees it. */
    public String clientIp(HttpServletRequest request) {
        return ClientIp.of(request, properties.trustedProxies());
    }

    /** Counts one attempt against the caller's address, and against the email if the action sends mail or checks a code. */
    public void check(Action action, String ip, String email) {
        if (!properties.enabled()) {
            return;
        }
        hit("ip:" + action + ":" + ip, action.perIp, action.ipWindow);
        if (email != null && !email.isBlank() && action != Action.LOGIN) {
            int limit = action == Action.VERIFY_OTP ? MAX_CODE_ATTEMPTS_PER_EMAIL : MAX_REQUESTS_PER_EMAIL;
            hit("email:" + action + ":" + normalize(email), limit, EMAIL_WINDOW);
        }
    }

    /** Before a login: refuses if this email has already failed too often recently. */
    public void checkLogin(String ip, String email) {
        if (!properties.enabled()) {
            return;
        }
        hit("ip:" + Action.LOGIN + ":" + ip, Action.LOGIN.perIp, Action.LOGIN.ipWindow);
        if (email != null && !email.isBlank()) {
            Deque<Instant> failures = windows.get("fail:" + normalize(email));
            if (failures != null) {
                synchronized (failures) {
                    prune(failures, clock.instant(), EMAIL_WINDOW);
                    if (failures.size() >= MAX_FAILED_LOGINS_PER_EMAIL) {
                        throw tooMany(failures.peekFirst(), clock.instant(), EMAIL_WINDOW,
                                "Too many failed logins for this account. Please try again later.");
                    }
                }
            }
        }
    }

    public void loginFailed(String email) {
        if (!properties.enabled() || email == null || email.isBlank()) {
            return;
        }
        Deque<Instant> failures = windows.computeIfAbsent("fail:" + normalize(email), k -> new ArrayDeque<>());
        synchronized (failures) {
            failures.addLast(clock.instant());
        }
    }

    /** A correct password clears the email's failure count. */
    public void loginSucceeded(String email) {
        if (email != null) {
            windows.remove("fail:" + normalize(email));
        }
    }

    private void hit(String key, int limit, Duration window) {
        Instant now = clock.instant();
        Deque<Instant> deque = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, now, window);
            if (deque.size() >= limit) {
                throw tooMany(deque.peekFirst(), now, window, "Too many requests. Please wait a moment and try again.");
            }
            deque.addLast(now);
        }
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || !e.getValue().peekLast().isAfter(now.minus(EMAIL_WINDOW));
                }
            });
        }
    }

    private static void prune(Deque<Instant> deque, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
            deque.pollFirst();
        }
    }

    private static TooManyRequestsException tooMany(Instant oldest, Instant now, Duration window, String message) {
        long retryAfter = Math.max(1, Duration.between(now, oldest.plus(window)).toSeconds() + 1);
        return new TooManyRequestsException(message, retryAfter);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
