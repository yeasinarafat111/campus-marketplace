package com.campus.marketplace.security;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityConfigTest.Endpoints.class)
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private UserPrincipal student;
    private UserPrincipal admin;
    private UserPrincipal disabled;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        student = save("student@campus.edu", Role.STUDENT, true);
        admin = save("admin@campus.edu", Role.ADMIN, true);
        disabled = save("disabled@campus.edu", Role.STUDENT, false);
    }

    @Test
    void passwordEncoder_isBCrypt() {
        assertThat(passwordEncoder.encode("secret")).startsWith("$2");
    }

    @Test
    void authRoutes_arePublic() throws Exception {
        mockMvc.perform(get("/api/auth/ping")).andExpect(status().isOk());
    }

    @Test
    void authRoutes_stayPublicEvenWithBadToken() throws Exception {
        mockMvc.perform(get("/api/auth/ping").header("Authorization", "Bearer garbage"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedRoute_withoutToken_returns401Json() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.path").value("/api/me"));
    }

    @Test
    void protectedRoute_withValidAccessToken_setsSecurityContext() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", bearer(jwtService.generateAccessToken(student))))
                .andExpect(status().isOk())
                .andExpect(content().string("student@campus.edu"));
    }

    @Test
    void protectedRoute_withRefreshToken_returns401() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", bearer(jwtService.generateRefreshToken(student))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid access token"));
    }

    @Test
    void protectedRoute_withGarbageToken_returns401() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid access token"));
    }

    @Test
    void protectedRoute_forDisabledUser_returns401() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", bearer(jwtService.generateAccessToken(disabled))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account is not enabled"));
    }

    @Test
    void protectedRoute_forDeletedUser_returns401() throws Exception {
        String token = jwtService.generateAccessToken(student);
        userRepository.deleteAll();
        mockMvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoute_asStudent_returns403Json() throws Exception {
        mockMvc.perform(get("/api/admin/stats").header("Authorization", bearer(jwtService.generateAccessToken(student))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void adminRoute_asAdmin_isAllowed() throws Exception {
        mockMvc.perform(get("/api/admin/stats").header("Authorization", bearer(jwtService.generateAccessToken(admin))))
                .andExpect(status().isOk());
    }

    @Test
    void adminRoute_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void noSessionCookieIsCreated() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", bearer(jwtService.generateAccessToken(student))))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    private UserPrincipal save(String email, Role role, boolean enabled) {
        User user = userRepository.save(User.builder()
                .fullName("Test")
                .email(email)
                .password(passwordEncoder.encode("password"))
                .role(role)
                .enabled(enabled)
                .build());
        return UserPrincipal.from(user);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @TestConfiguration
    @RestController
    static class Endpoints {

        @GetMapping("/api/auth/ping")
        String ping() {
            return "pong";
        }

        @GetMapping("/api/me")
        String me(@AuthenticationPrincipal UserPrincipal principal) {
            return principal.getEmail();
        }

        @GetMapping("/api/admin/stats")
        String stats() {
            return "ok";
        }
    }
}
