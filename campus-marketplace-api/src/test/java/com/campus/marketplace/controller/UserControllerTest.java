package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    private static final String PASSWORD = "correct-horse";
    private static final String PHONE_ERROR = "Phone number must be a valid Bangladeshi mobile number, like 01XXXXXXXXX";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private User alice;
    private User bob;
    private User legacy;

    @BeforeEach
    void setUp() {
        listingRepository.deleteAll();
        userRepository.deleteAll();
        alice = saveUser("alice@seu.edu.bd", "Alice Smith", "+8801711111111");
        bob = saveUser("bob@seu.edu.bd", "Bob Jones", "+8801722222222");
        legacy = saveUser("legacy@seu.edu.bd", "Old Timer", null); // account from before phones existed
    }

    // ---- GET /me ----

    @Test
    void me_returnsFullProfile() throws Exception {
        mockMvc.perform(auth(get("/api/users/me"), token(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.getId()))
                .andExpect(jsonPath("$.fullName").value("Alice Smith"))
                .andExpect(jsonPath("$.email").value("alice@seu.edu.bd"))
                .andExpect(jsonPath("$.phone").value("+8801711111111"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void me_forLegacyUserWithoutPhone_hasNullPhone() throws Exception {
        mockMvc.perform(auth(get("/api/users/me"), token(legacy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").isEmpty());
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- PATCH /me ----

    @Test
    void patch_updatesNameAndPhone_normalizingThePhone() throws Exception {
        mockMvc.perform(json(patch("/api/users/me"), token(alice), """
                {"fullName":"  Alice B. Smith ","phone":"017 3333-3333"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice B. Smith"))
                .andExpect(jsonPath("$.phone").value("+8801733333333"));

        User saved = userRepository.findById(alice.getId()).orElseThrow();
        assertThat(saved.getFullName()).isEqualTo("Alice B. Smith");
        assertThat(saved.getPhone()).isEqualTo("+8801733333333");
    }

    @Test
    void patch_isPartial_omittedFieldsAreUnchanged() throws Exception {
        mockMvc.perform(json(patch("/api/users/me"), token(alice), """
                {"fullName":"Alice Renamed"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+8801711111111"));
        mockMvc.perform(json(patch("/api/users/me"), token(alice), """
                {"phone":"01744444444"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice Renamed"))
                .andExpect(jsonPath("$.phone").value("+8801744444444"));
        mockMvc.perform(json(patch("/api/users/me"), token(alice), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice Renamed"));
    }

    @Test
    void patch_legacyUserCanAddAPhone() throws Exception {
        mockMvc.perform(json(patch("/api/users/me"), token(legacy), """
                {"phone":"8801755555555"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+8801755555555"));
    }

    @Test
    void patch_invalidInput_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(json(patch("/api/users/me"), token(alice), """
                {"fullName":"%s","phone":"12345"}""".formatted("x".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fullName").value("Full name must be at most 100 characters"))
                .andExpect(jsonPath("$.fieldErrors.phone").value(PHONE_ERROR));
        mockMvc.perform(json(patch("/api/users/me"), token(alice), """
                {"fullName":"   ","phone":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fullName").value("Full name must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.phone").exists());

        User saved = userRepository.findById(alice.getId()).orElseThrow();
        assertThat(saved.getFullName()).isEqualTo("Alice Smith");
        assertThat(saved.getPhone()).isEqualTo("+8801711111111");
    }

    @Test
    void patch_phoneOfAnotherUser_returns409_inAnyFormat() throws Exception {
        for (String phone : new String[] {"01722222222", "8801722222222", "+8801722222222"}) {
            mockMvc.perform(json(patch("/api/users/me"), token(alice), "{\"phone\":\"%s\"}".formatted(phone)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Phone number is already registered"));
        }
        assertThat(userRepository.findById(alice.getId()).orElseThrow().getPhone()).isEqualTo("+8801711111111");
    }

    @Test
    void patch_ownCurrentPhone_isNotADuplicate() throws Exception {
        mockMvc.perform(json(patch("/api/users/me"), token(alice), """
                {"phone":"01711111111"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+8801711111111"));
    }

    @Test
    void patch_ignoresEmailRoleAndId() throws Exception {
        mockMvc.perform(json(patch("/api/users/me"), token(alice), """
                {"email":"hacker@seu.edu.bd","role":"ADMIN","id":999,"fullName":"Still Alice"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@seu.edu.bd"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.id").value(alice.getId()));
    }

    // ---- POST /me/password ----

    @Test
    void changePassword_wrongCurrentPassword_returns400_andChangesNothing() throws Exception {
        mockMvc.perform(json(post("/api/users/me/password"), token(alice), """
                {"currentPassword":"not-my-password","newPassword":"brand-new-pass"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));

        User saved = userRepository.findById(alice.getId()).orElseThrow();
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPassword())).isTrue();
        assertThat(saved.getPasswordChangedAt()).isNull();
    }

    @Test
    void changePassword_validatesNewPassword() throws Exception {
        mockMvc.perform(json(post("/api/users/me/password"), token(alice), """
                {"currentPassword":"","newPassword":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currentPassword").value("Current password is required"))
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("Password must be between 8 and 72 characters"));
        mockMvc.perform(json(post("/api/users/me/password"), token(alice), """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted(PASSWORD, "a".repeat(73))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_endsOtherSessions_butKeepsTheCurrentOneWithFreshTokens() throws Exception {
        JsonNode oldTokens = readJson(login("alice@seu.edu.bd", PASSWORD).andExpect(status().isOk()));
        String oldAccess = oldTokens.get("accessToken").asText();
        String oldRefresh = oldTokens.get("refreshToken").asText();
        mockMvc.perform(auth(get("/api/users/me"), oldAccess)).andExpect(status().isOk());

        // JWT timestamps are whole seconds; make sure the change lands in a later second than the login.
        Thread.sleep(1100);
        JsonNode fresh = readJson(mockMvc.perform(json(post("/api/users/me/password"), oldAccess, """
                {"currentPassword":"%s","newPassword":"brand-new-pass"}""".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber()));

        // The other session's tokens are dead...
        refresh(oldRefresh).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Password was changed. Please log in again."));
        mockMvc.perform(auth(get("/api/users/me"), oldAccess)).andExpect(status().isUnauthorized());

        // ...while the tokens returned by the change keep working.
        mockMvc.perform(auth(get("/api/users/me"), fresh.get("accessToken").asText())).andExpect(status().isOk());
        refresh(fresh.get("refreshToken").asText()).andExpect(status().isOk());

        // The new password works, the old one doesn't.
        login("alice@seu.edu.bd", "brand-new-pass").andExpect(status().isOk());
        login("alice@seu.edu.bd", PASSWORD).andExpect(status().isUnauthorized());
    }

    // ---- privacy ----

    @Test
    void phoneNumbersNeverAppearInPublicListingResponses() throws Exception {
        Listing listing = listingRepository.save(Listing.builder().title("Desk lamp")
                .price(new BigDecimal("12.00")).category(Category.OTHER).owner(alice).build());

        for (String url : new String[] {"/api/listings", "/api/listings/" + listing.getId()}) {
            String body = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("Desk lamp").doesNotContain("8801711111111").doesNotContain("phone");
        }
        String bobsView = mockMvc.perform(auth(get("/api/listings/" + listing.getId()), token(bob)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(bobsView).doesNotContain("8801711111111").doesNotContain("phone");
    }

    // ---- helpers ----

    private User saveUser(String email, String name, String phone) {
        return userRepository.save(User.builder().fullName(name).email(email).phone(phone)
                .password(passwordEncoder.encode(PASSWORD)).enabled(true).build());
    }

    private String token(User user) {
        return jwtService.generateAccessToken(UserPrincipal.from(user));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", refreshToken))));
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
        return auth(request, token).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
