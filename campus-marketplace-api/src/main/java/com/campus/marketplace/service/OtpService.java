package com.campus.marketplace.service;

import com.campus.marketplace.config.OtpProperties;
import com.campus.marketplace.entity.OtpPurpose;
import com.campus.marketplace.entity.OtpToken;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.InvalidOtpException;
import com.campus.marketplace.exception.TooManyRequestsException;
import com.campus.marketplace.repository.OtpTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpTokenRepository otpTokenRepository;
    private final OtpProperties otpProperties;

    /**
     * Creates a fresh 6-digit code for the user and purpose. Any earlier unused codes for the same
     * purpose are invalidated, so only the most recently emailed code works.
     *
     * @return the plain code, to be emailed to the user
     */
    @Transactional
    public String issue(User user, OtpPurpose purpose) {
        otpTokenRepository.invalidateActive(user, purpose);

        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        otpTokenRepository.save(OtpToken.builder()
                .user(user)
                .code(code)
                .purpose(purpose)
                .expiresAt(Instant.now().plus(otpProperties.expiration()))
                .build());
        return code;
    }

    /**
     * Throws if a code for this user and purpose was issued less than {@code resendCooldown} ago.
     */
    @Transactional(readOnly = true)
    public void checkResendCooldown(User user, OtpPurpose purpose) {
        otpTokenRepository.findTopByUserAndPurposeOrderByIdDesc(user, purpose).ifPresent(last -> {
            Duration sinceLast = Duration.between(last.getCreatedAt(), Instant.now());
            Duration remaining = otpProperties.resendCooldown().minus(sinceLast);
            if (!remaining.isNegative() && !remaining.isZero()) {
                long seconds = Math.max(1, (remaining.toMillis() + 999) / 1000);
                throw new TooManyRequestsException(
                        "Please wait " + seconds + " seconds before requesting another code", seconds);
            }
        });
    }

    /**
     * Consumes the user's current code if {@code code} matches it. Wrong guesses are counted against the
     * current code, which is invalidated after {@code maxAttempts}.
     * <p>
     * Must run in a transaction that is NOT rolled back on {@link InvalidOtpException}, so the attempt
     * counter is saved even though the request fails.
     *
     * @throws InvalidOtpException if the code is wrong, expired, already used, or attempts are exhausted
     */
    public void consume(User user, OtpPurpose purpose, String code) {
        Instant now = Instant.now();
        Optional<OtpToken> active = otpTokenRepository.findTopByUserAndPurposeAndUsedFalseOrderByExpiresAtDesc(user, purpose);

        if (active.isPresent() && matches(active.get().getCode(), code)) {
            OtpToken token = active.get();
            if (token.isExpired(now)) {
                throw new InvalidOtpException("Verification code has expired. Please request a new one.");
            }
            token.setUsed(true);
            return;
        }

        // Wrong code: count it against the current code, if there is a live one.
        if (active.isPresent() && !active.get().isExpired(now)) {
            OtpToken token = active.get();
            token.setAttempts(token.getAttempts() + 1);
            if (token.getAttempts() >= otpProperties.maxAttempts()) {
                token.setUsed(true);
                throw new InvalidOtpException("Too many incorrect attempts. Please request a new code.");
            }
        }

        boolean previouslyUsed = otpTokenRepository.findTopByUserAndPurposeAndCodeOrderByIdDesc(user, purpose, code)
                .filter(OtpToken::isUsed)
                .isPresent();
        throw new InvalidOtpException(previouslyUsed
                ? "This verification code has already been used"
                : "Invalid verification code");
    }

    private static boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
