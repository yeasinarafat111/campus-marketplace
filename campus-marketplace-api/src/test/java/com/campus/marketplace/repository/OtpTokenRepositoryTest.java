package com.campus.marketplace.repository;

import com.campus.marketplace.entity.OtpPurpose;
import com.campus.marketplace.entity.OtpToken;
import com.campus.marketplace.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OtpTokenRepositoryTest {

    @Autowired private OtpTokenRepository otpTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .fullName("Alice").email("alice@seu.edu.bd").password("hashed").build());
    }

    @Test
    void savesWithDefaults_andKeepsLeadingZeros() {
        OtpToken saved = otpTokenRepository.saveAndFlush(otp("004217", OtpPurpose.SIGNUP, 10));
        entityManager.clear();

        OtpToken found = otpTokenRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getCode()).isEqualTo("004217");
        assertThat(found.getPurpose()).isEqualTo(OtpPurpose.SIGNUP);
        assertThat(found.isUsed()).isFalse();
        assertThat(found.getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    void findLatestUnused_skipsUsedAndOtherPurposes() {
        otpTokenRepository.save(otp("111111", OtpPurpose.SIGNUP, 5));
        otpTokenRepository.save(otp("222222", OtpPurpose.SIGNUP, 10));
        OtpToken used = otp("333333", OtpPurpose.SIGNUP, 15);
        used.setUsed(true);
        otpTokenRepository.save(used);
        otpTokenRepository.save(otp("444444", OtpPurpose.RESET_PASSWORD, 20));

        assertThat(otpTokenRepository.findTopByUserAndPurposeAndUsedFalseOrderByExpiresAtDesc(user, OtpPurpose.SIGNUP))
                .map(OtpToken::getCode).contains("222222");
        assertThat(otpTokenRepository.findTopByUserAndPurposeAndUsedFalseOrderByExpiresAtDesc(user, OtpPurpose.RESET_PASSWORD))
                .map(OtpToken::getCode).contains("444444");
    }

    @Test
    void isExpired() {
        OtpToken token = otp("123456", OtpPurpose.SIGNUP, 10);
        assertThat(token.isExpired(Instant.now())).isFalse();
        assertThat(token.isExpired(token.getExpiresAt())).isTrue();
    }

    @Test
    void deletingUser_deletesTheirOtps() {
        otpTokenRepository.saveAndFlush(otp("123456", OtpPurpose.SIGNUP, 10));
        entityManager.clear();

        userRepository.deleteById(user.getId());
        userRepository.flush();

        assertThat(otpTokenRepository.count()).isZero();
    }

    private OtpToken otp(String code, OtpPurpose purpose, int minutesFromNow) {
        return OtpToken.builder()
                .user(user)
                .code(code)
                .purpose(purpose)
                .expiresAt(Instant.now().plus(minutesFromNow, ChronoUnit.MINUTES))
                .build();
    }
}
