package com.campus.marketplace.controller;

import com.campus.marketplace.entity.OtpPurpose;
import com.campus.marketplace.entity.OtpToken;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.EmailDeliveryException;
import com.campus.marketplace.repository.OtpTokenRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    private static final String PASSWORD = "correct-horse";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private OtpTokenRepository otpTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Never talk to a real SMTP server from tests.
    @MockitoBean private MailService mailService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    // ---- signup ----

    @Test
    void signup_storesSignupOtp_andEmailsTheSameCode() throws Exception {
        postJson("/api/auth/signup", """
                {"fullName":"Olga","email":"olga@seu.edu.bd","password":"%s"}""".formatted(PASSWORD))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail("olga@seu.edu.bd").orElseThrow();
        OtpToken otp = otpTokenRepository.findTopByUserAndPurposeAndUsedFalseOrderByExpiresAtDesc(user, OtpPurpose.SIGNUP)
                .orElseThrow();
        assertThat(otp.getCode()).matches("\\d{6}");
        assertThat(otp.getExpiresAt()).isAfter(java.time.Instant.now().plus(java.time.Duration.ofMinutes(9)));
        verify(mailService).sendOtpEmail("olga@seu.edu.bd", otp.getCode());
    }

    @Test
    void signup_whenEmailFails_returns503_andRollsBackUserAndOtp() throws Exception {
        doThrow(new EmailDeliveryException("smtp down", null)).when(mailService).sendOtpEmail(anyString(), anyString());

        postJson("/api/auth/signup", """
                {"fullName":"Pat","email":"pat@seu.edu.bd","password":"%s"}""".formatted(PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Could not send email right now. Please try again later."));

        assertThat(userRepository.findByEmail("pat@seu.edu.bd")).isEmpty();
        assertThat(otpTokenRepository.count()).isZero();
    }

    @Test
    void signup_duplicateEmail_doesNotSendEmail() throws Exception {
        saveUser("dup@seu.edu.bd", false);

        postJson("/api/auth/signup", """
                {"fullName":"Dup","email":"dup@seu.edu.bd","password":"%s"}""".formatted(PASSWORD))
                .andExpect(status().isConflict());

        verify(mailService, never()).sendOtpEmail(any(), any());
    }

    @Test
    void signup_createsDisabledStudentWithHashedPassword_andReturnsNoTokens() throws Exception {
        postJson("/api/auth/signup", """
                {"fullName":"Alice Smith","email":"  Alice@SEU.edu.bd ","password":"%s"}""".formatted(PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("alice@seu.edu.bd"))
                .andExpect(jsonPath("$.fullName").value("Alice Smith"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        User saved = userRepository.findByEmail("alice@seu.edu.bd").orElseThrow();
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getRole()).isEqualTo(Role.STUDENT);
        assertThat(saved.getPassword()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPassword())).isTrue();
    }

    @Test
    void signup_ignoresClientSuppliedRoleAndEnabled() throws Exception {
        postJson("/api/auth/signup", """
                {"fullName":"Mallory","email":"mallory@seu.edu.bd","password":"%s","role":"ADMIN","enabled":true}"""
                .formatted(PASSWORD))
                .andExpect(status().isCreated());

        User saved = userRepository.findByEmail("mallory@seu.edu.bd").orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.STUDENT);
        assertThat(saved.isEnabled()).isFalse();
    }

    @Test
    void signup_duplicateEmail_returns409_caseInsensitive() throws Exception {
        saveUser("bob@seu.edu.bd", true);

        postJson("/api/auth/signup", """
                {"fullName":"Bob","email":"BOB@seu.edu.bd","password":"%s"}""".formatted(PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email is already registered"));
    }

    @Test
    void signup_invalidBody_returns400WithFieldErrors() throws Exception {
        postJson("/api/auth/signup", """
                {"fullName":" ","email":"not-an-email","password":"short"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.fullName").value("Full name is required"))
                .andExpect(jsonPath("$.fieldErrors.email").value("Email must be a valid email address"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Password must be between 8 and 72 characters"));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void signup_emailOutsideAllowedDomain_returns400WithDomainMessage() throws Exception {
        for (String email : new String[] {
                "alice@gmail.com",
                "alice@cse.seu.edu.bd",          // subdomain
                "alice@seu.edu.bd.example.com",  // look-alike suffix
                "alice@notseu.edu.bd",           // different domain sharing the suffix
                "alice@seu.edu"}) {
            postJson("/api/auth/signup", """
                    {"fullName":"Alice","email":"%s","password":"%s"}""".formatted(email, PASSWORD))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.email").value("Only @seu.edu.bd email addresses can register"));
        }
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void signup_malformedEmail_reportsFormatErrorNotDomainError() throws Exception {
        for (String email : new String[] {"not-an-email", "alice@", "@seu.edu.bd", "a@b@seu.edu.bd", "al ice@gmail.com"}) {
            postJson("/api/auth/signup", """
                    {"fullName":"Alice","email":"%s","password":"%s"}""".formatted(email, PASSWORD))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.email").value("Email must be a valid email address"));
        }
    }

    @Test
    void signup_passwordLongerThan72_returns400() throws Exception {
        postJson("/api/auth/signup", """
                {"fullName":"Long","email":"long@seu.edu.bd","password":"%s"}""".formatted("a".repeat(73)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    // ---- verify-otp ----

    @Test
    void verifyOtp_correctCode_enablesUser_thenSameCodeIsRejectedAsUsed() throws Exception {
        String code = signupAndGetCode("vera@seu.edu.bd");

        verifyOtp("vera@seu.edu.bd", code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("vera@seu.edu.bd"))
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(userRepository.findByEmail("vera@seu.edu.bd").orElseThrow().isEnabled()).isTrue();

        verifyOtp("vera@seu.edu.bd", code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This verification code has already been used"));

        login("vera@seu.edu.bd", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void verifyOtp_wrongCode_isRejected_andUserStaysDisabled() throws Exception {
        String code = signupAndGetCode("wes@seu.edu.bd");
        String wrong = code.equals("000000") ? "111111" : "000000";

        verifyOtp("wes@seu.edu.bd", wrong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification code"));
        assertThat(userRepository.findByEmail("wes@seu.edu.bd").orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void verifyOtp_lockedAfterMaxAttempts_evenCorrectCodeThenFails() throws Exception {
        String code = signupAndGetCode("lock@seu.edu.bd");
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 4; i++) {
            verifyOtp("lock@seu.edu.bd", wrong).andExpect(jsonPath("$.message").value("Invalid verification code"));
        }
        verifyOtp("lock@seu.edu.bd", wrong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Too many incorrect attempts. Please request a new code."));

        // The attempt counter was committed despite the 400s, so the real code no longer works.
        verifyOtp("lock@seu.edu.bd", code).andExpect(status().isBadRequest());
        assertThat(userRepository.findByEmail("lock@seu.edu.bd").orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void verifyOtp_expiredCode_isRejected() throws Exception {
        String code = signupAndGetCode("exp@seu.edu.bd");
        jdbcTemplate.update("update otp_tokens set expires_at = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));

        verifyOtp("exp@seu.edu.bd", code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verification code has expired. Please request a new one."));
    }

    @Test
    void verifyOtp_unknownEmail_looksLikeWrongCode() throws Exception {
        verifyOtp("nobody@seu.edu.bd", "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification code"));
    }

    @Test
    void verifyOtp_malformedCode_returns400() throws Exception {
        verifyOtp("x@seu.edu.bd", "12ab")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.code").value("Code must be 6 digits"));
    }

    // ---- resend-otp ----

    @Test
    void resendOtp_withinCooldown_returns429WithRetryAfter() throws Exception {
        signupAndGetCode("cool@seu.edu.bd");

        resend("cool@seu.edu.bd")
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
    }

    @Test
    void resendOtp_afterCooldown_sendsNewCode_andOldCodeStopsWorking() throws Exception {
        String oldCode = signupAndGetCode("res@seu.edu.bd");
        jdbcTemplate.update("update otp_tokens set created_at = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(61)));

        resend("res@seu.edu.bd").andExpect(status().isOk());

        User user = userRepository.findByEmail("res@seu.edu.bd").orElseThrow();
        String newCode = otpTokenRepository.findTopByUserAndPurposeAndUsedFalseOrderByExpiresAtDesc(user, OtpPurpose.SIGNUP)
                .orElseThrow().getCode();
        verify(mailService).sendOtpEmail("res@seu.edu.bd", newCode);
        if (!newCode.equals(oldCode)) {
            verifyOtp("res@seu.edu.bd", oldCode).andExpect(status().isBadRequest());
        }
        verifyOtp("res@seu.edu.bd", newCode).andExpect(status().isOk());
    }

    @Test
    void resendOtp_unknownOrVerifiedEmail_returnsSameGenericResponse_andSendsNothing() throws Exception {
        saveUser("done@seu.edu.bd", true);

        String unknown = resend("ghost@seu.edu.bd").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String verified = resend("done@seu.edu.bd").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(verified).isEqualTo(unknown);
        verify(mailService, never()).sendOtpEmail(any(), any());
    }

    // ---- login ----

    @Test
    void login_unverifiedUserWithCorrectPassword_returns403VerifyEmail() throws Exception {
        saveUser("new@seu.edu.bd", false);

        login("new@seu.edu.bd", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Please verify your email"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void login_unverifiedUserWithWrongPassword_returns401_notVerifyEmail() throws Exception {
        saveUser("new@seu.edu.bd", false);

        login("new@seu.edu.bd", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void login_signupThenLogin_isRejectedUntilVerified() throws Exception {
        postJson("/api/auth/signup", """
                {"fullName":"Carol","email":"carol@seu.edu.bd","password":"%s"}""".formatted(PASSWORD))
                .andExpect(status().isCreated());

        login("carol@seu.edu.bd", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Please verify your email"));
    }

    @Test
    void login_verifiedUser_returnsAccessAndRefreshTokens() throws Exception {
        saveUser("dave@seu.edu.bd", true);

        JsonNode body = readJson(login("DAVE@seu.edu.bd", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900)));

        assertThat(jwtService.validateAccessToken(body.get("accessToken").asText())).isEqualTo("dave@seu.edu.bd");
        assertThat(jwtService.validateRefreshToken(body.get("refreshToken").asText())).isEqualTo("dave@seu.edu.bd");
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        saveUser("erin@seu.edu.bd", true);

        login("erin@seu.edu.bd", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void login_unknownEmail_returnsSame401AsWrongPassword() throws Exception {
        login("ghost@seu.edu.bd", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void login_invalidBody_returns400() throws Exception {
        postJson("/api/auth/login", """
                {"email":"nope","password":""}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    // ---- refresh ----

    @Test
    void refresh_withValidRefreshToken_returnsNewAccessToken() throws Exception {
        UserPrincipal user = saveUser("frank@seu.edu.bd", true);

        JsonNode body = readJson(refresh(jwtService.generateRefreshToken(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").doesNotExist()));

        assertThat(jwtService.validateAccessToken(body.get("accessToken").asText())).isEqualTo("frank@seu.edu.bd");
    }

    @Test
    void refresh_withAccessToken_returns401() throws Exception {
        UserPrincipal user = saveUser("grace@seu.edu.bd", true);

        refresh(jwtService.generateAccessToken(user))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    void refresh_withGarbage_returns401() throws Exception {
        refresh("garbage")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    void refresh_forDisabledUser_returns401() throws Exception {
        UserPrincipal user = saveUser("heidi@seu.edu.bd", false);

        refresh(jwtService.generateRefreshToken(user))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account is not enabled"));
    }

    @Test
    void refresh_forDeletedUser_returns401() throws Exception {
        String token = jwtService.generateRefreshToken(saveUser("ivan@seu.edu.bd", true));
        userRepository.deleteAll();

        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_blankToken_returns400() throws Exception {
        postJson("/api/auth/refresh", """
                {"refreshToken":""}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.refreshToken").value("Refresh token is required"));
    }

    // ---- helpers ----

    private UserPrincipal saveUser(String email, boolean enabled) {
        return UserPrincipal.from(userRepository.save(User.builder()
                .fullName("Test User")
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .enabled(enabled)
                .build()));
    }

    private String signupAndGetCode(String email) throws Exception {
        postJson("/api/auth/signup", """
                {"fullName":"Test","email":"%s","password":"%s"}""".formatted(email, PASSWORD))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        return otpTokenRepository.findTopByUserAndPurposeAndUsedFalseOrderByExpiresAtDesc(user, OtpPurpose.SIGNUP)
                .orElseThrow().getCode();
    }

    private ResultActions verifyOtp(String email, String code) throws Exception {
        return postJson("/api/auth/verify-otp", objectMapper.writeValueAsString(
                java.util.Map.of("email", email, "code", code)));
    }

    private ResultActions resend(String email) throws Exception {
        return postJson("/api/auth/resend-otp", objectMapper.writeValueAsString(java.util.Map.of("email", email)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return postJson("/api/auth/login", objectMapper.writeValueAsString(
                java.util.Map.of("email", email, "password", password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return postJson("/api/auth/refresh", objectMapper.writeValueAsString(
                java.util.Map.of("refreshToken", refreshToken)));
    }

    private ResultActions postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
