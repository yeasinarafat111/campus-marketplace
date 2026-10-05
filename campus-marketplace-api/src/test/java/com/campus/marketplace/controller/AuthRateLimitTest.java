package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.service.MailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, signup, forgot-password, verify-otp and resend-otp limits, with a clock the test moves by hand. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthRateLimitTest.FakeClock.class)
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class AuthRateLimitTest {

    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-10-05T10:00:00Z"));

    @TestConfiguration
    static class FakeClock {
        @Bean
        @Primary
        Clock testClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private MailService mailService;

    @Autowired private com.campus.marketplace.service.AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter.reset();
        NOW.set(Instant.parse("2026-10-05T10:00:00Z"));
        userRepository.deleteAll();
        userRepository.save(User.builder().fullName("Real User").email("real@seu.edu.bd")
                .password(passwordEncoder.encode("correct-horse")).role(Role.STUDENT).enabled(true).build());
    }

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    private void advance(Duration d) {
        NOW.updateAndGet(t -> t.plus(d));
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(req -> { req.setRemoteAddr(ip); return req; }));
    }

    private ResultActions postJson(String path, String body, String ip) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(req -> { req.setRemoteAddr(ip); return req; }));
    }

    // ---- login, per IP ----

    @Test
    void tenLoginAttemptsAMinuteFromOneAddress_theEleventhIs429WithRetryAfter() throws Exception {
        for (int i = 0; i < 10; i++) {
            login("nobody" + i + "@seu.edu.bd", "x", "10.0.0.1").andExpect(status().isUnauthorized());
        }
        login("nobody-again@seu.edu.bd", "x", "10.0.0.1").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Too many requests. Please wait a moment and try again."));
        // Another address is unaffected.
        login("nobody@seu.edu.bd", "x", "10.0.0.2").andExpect(status().isUnauthorized());
    }

    @Test
    void theWindowSlides_soTheAddressIsAllowedAgainAfterAMinute() throws Exception {
        for (int i = 0; i < 10; i++) {
            login("n" + i + "@seu.edu.bd", "x", "10.0.0.1");
        }
        login("n@seu.edu.bd", "x", "10.0.0.1").andExpect(status().isTooManyRequests());

        advance(Duration.ofSeconds(61));

        login("n@seu.edu.bd", "x", "10.0.0.1").andExpect(status().isUnauthorized());
    }

    @Test
    void successfulLoginsCountTowardTheAddressLimitToo() throws Exception {
        for (int i = 0; i < 10; i++) {
            login("real@seu.edu.bd", "correct-horse", "10.0.0.3").andExpect(status().isOk());
        }
        login("real@seu.edu.bd", "correct-horse", "10.0.0.3").andExpect(status().isTooManyRequests());
    }

    // ---- login, per email ----

    @Test
    void fiveFailedLoginsForOneEmail_lockItOut_fromAnyAddress_untilTheWindowPasses() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("real@seu.edu.bd", "wrong-" + i, "10.1.0." + i).andExpect(status().isUnauthorized());
        }
        // Locked out: even the right password, even from a fresh address.
        login("real@seu.edu.bd", "correct-horse", "10.1.1.1").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value("Too many failed logins for this account. Please try again later."));
        // Other accounts are not affected.
        login("someone-else@seu.edu.bd", "x", "10.1.1.1").andExpect(status().isUnauthorized());

        advance(Duration.ofMinutes(14));
        login("real@seu.edu.bd", "correct-horse", "10.1.1.2").andExpect(status().isTooManyRequests());
        advance(Duration.ofMinutes(2));   // 16 minutes after the failures
        login("real@seu.edu.bd", "correct-horse", "10.1.1.3").andExpect(status().isOk());
    }

    @Test
    void aSuccessfulLoginClearsTheFailureCount() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("real@seu.edu.bd", "wrong", "10.2.0." + i).andExpect(status().isUnauthorized());
        }
        login("real@seu.edu.bd", "correct-horse", "10.2.1.1").andExpect(status().isOk());
        // Four more failures are fine: the earlier four were forgotten.
        for (int i = 0; i < 4; i++) {
            login("real@seu.edu.bd", "wrong", "10.2.2." + i).andExpect(status().isUnauthorized());
        }
        login("real@seu.edu.bd", "correct-horse", "10.2.3.1").andExpect(status().isOk());
    }

    @Test
    void theEmailIsMatchedCaseInsensitively() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(i % 2 == 0 ? "REAL@seu.edu.bd" : " real@SEU.edu.bd ", "wrong", "10.3.0." + i);
        }
        login("real@seu.edu.bd", "correct-horse", "10.3.1.1").andExpect(status().isTooManyRequests());
    }

    // ---- the client address behind Cloud Run ----

    @Test
    void aSpoofedForwardedForEntryDoesNotHelp_theRightmostEntryIsTheClient() throws Exception {
        for (int i = 0; i < 10; i++) {
            // The attacker invents a new left-hand address each time; Cloud Run appends the real one on the right.
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Forwarded-For", "6.6.6." + i + ", 203.0.113.9")
                            .content("{\"email\":\"n" + i + "@seu.edu.bd\",\"password\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "6.6.6.99, 203.0.113.9")
                        .content("{\"email\":\"n@seu.edu.bd\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests());
        // A different real client is counted separately.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "6.6.6.99, 203.0.113.10")
                        .content("{\"email\":\"n@seu.edu.bd\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withoutTheHeader_theConnectionAddressIsUsed() throws Exception {
        for (int i = 0; i < 10; i++) {
            login("n" + i + "@seu.edu.bd", "x", "10.4.0.1");
        }
        login("n@seu.edu.bd", "x", "10.4.0.1").andExpect(status().isTooManyRequests());
    }

    // ---- the other endpoints ----

    @Test
    void signup_isLimitedPerAddress_andPerEmail() throws Exception {
        for (int i = 0; i < 5; i++) {
            postJson("/api/auth/signup", signupBody("new" + i + "@seu.edu.bd", "0171234567" + i), "10.5.0.1").andExpect(status().isCreated());
        }
        postJson("/api/auth/signup", signupBody("new6@seu.edu.bd", "01712345679"), "10.5.0.1").andExpect(status().isTooManyRequests());
        // Same email five times from five addresses: the email limit applies (the 6th is refused).
        for (int i = 0; i < 5; i++) {
            postJson("/api/auth/signup", signupBody("dup@seu.edu.bd", "01812345678"), "10.5.1." + i);
        }
        postJson("/api/auth/signup", signupBody("dup@seu.edu.bd", "01812345678"), "10.5.2.1").andExpect(status().isTooManyRequests());
    }

    @Test
    void forgotPassword_andResendOtp_areLimitedPerEmail_evenFromManyAddresses() throws Exception {
        for (String path : new String[] {"/api/auth/forgot-password", "/api/auth/resend-otp"}) {
            for (int i = 0; i < 5; i++) {
                postJson(path, "{\"email\":\"victim@seu.edu.bd\"}", "10.6." + path.length() + "." + i).andExpect(status().isOk());
            }
            postJson(path, "{\"email\":\"victim@seu.edu.bd\"}", "10.6.9.9").andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));
        }
    }

    @Test
    void forgotPassword_isLimitedPerAddress() throws Exception {
        for (int i = 0; i < 5; i++) {
            postJson("/api/auth/forgot-password", "{\"email\":\"v" + i + "@seu.edu.bd\"}", "10.7.0.1").andExpect(status().isOk());
        }
        postJson("/api/auth/forgot-password", "{\"email\":\"v9@seu.edu.bd\"}", "10.7.0.1").andExpect(status().isTooManyRequests());
    }

    @Test
    void verifyOtp_andResetPassword_shareTheCodeGuessingLimits() throws Exception {
        for (int i = 0; i < 10; i++) {
            postJson("/api/auth/verify-otp", "{\"email\":\"real@seu.edu.bd\",\"code\":\"12345" + (i % 10) + "\"}", "10.8." + i + ".1");
        }
        // Ten attempts on this email in the window: the eleventh is refused, whichever endpoint it is.
        postJson("/api/auth/verify-otp", "{\"email\":\"real@seu.edu.bd\",\"code\":\"000000\"}", "10.8.99.1").andExpect(status().isTooManyRequests());
        postJson("/api/auth/reset-password", "{\"email\":\"real@seu.edu.bd\",\"code\":\"000000\",\"newPassword\":\"brand-new-pass\"}", "10.8.99.2")
                .andExpect(status().isTooManyRequests());

        advance(Duration.ofMinutes(16));
        postJson("/api/auth/verify-otp", "{\"email\":\"real@seu.edu.bd\",\"code\":\"000000\"}", "10.8.99.3").andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }

    @Test
    void otherEndpointsAreNotRateLimited() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/listings")).andExpect(status().isOk());
        }
        assertThat(true).isTrue();
    }

    private static String signupBody(String email, String phone) {
        return "{\"fullName\":\"New Person\",\"email\":\"" + email + "\",\"phone\":\"" + phone + "\",\"password\":\"correct-horse\"}";
    }
}
