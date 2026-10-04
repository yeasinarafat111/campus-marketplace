package com.campus.marketplace.service;

import com.campus.marketplace.config.OtpProperties;
import com.campus.marketplace.entity.OtpPurpose;
import com.campus.marketplace.entity.OtpToken;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.OtpTokenRepository;
import com.campus.marketplace.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(OtpService.class)
@EnableConfigurationProperties(OtpProperties.class)
class OtpServiceTest {

    @Autowired private OtpService otpService;
    @Autowired private OtpTokenRepository otpTokenRepository;
    @Autowired private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .fullName("Alice").email("alice@seu.edu.bd").password("hashed").build());
    }

    @Test
    void issue_createsSixDigitCodeExpiringPerConfig() {
        Instant before = Instant.now();
        String code = otpService.issue(user, OtpPurpose.SIGNUP);

        assertThat(code).matches("\\d{6}");
        OtpToken token = latest(OtpPurpose.SIGNUP);
        assertThat(token.getCode()).isEqualTo(code);
        assertThat(token.getExpiresAt()).isBetween(before.plus(Duration.ofMinutes(10)), Instant.now().plus(Duration.ofMinutes(10)));
    }

    @Test
    void issue_invalidatesPreviousCodesForSamePurposeOnly() {
        otpService.issue(user, OtpPurpose.SIGNUP);
        String reset = otpService.issue(user, OtpPurpose.RESET_PASSWORD);
        String second = otpService.issue(user, OtpPurpose.SIGNUP);

        assertThat(otpTokenRepository.findAll().stream().filter(t -> !t.isUsed()).map(OtpToken::getCode))
                .containsExactlyInAnyOrder(second, reset);
        assertThat(latest(OtpPurpose.SIGNUP).getCode()).isEqualTo(second);
    }

    private OtpToken latest(OtpPurpose purpose) {
        return otpTokenRepository.findTopByUserAndPurposeAndUsedFalseOrderByExpiresAtDesc(user, purpose).orElseThrow();
    }
}
