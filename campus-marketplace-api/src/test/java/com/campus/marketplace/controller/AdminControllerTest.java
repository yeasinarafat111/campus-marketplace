package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.OtpPurpose;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.MailService;
import com.campus.marketplace.service.OtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

    private static final String PASSWORD = "correct-horse";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private OtpService otpService;
    @MockitoBean private MailService mailService;

    private User admin;
    private User alice;
    private String adminToken;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        listingRepository.deleteAll();
        userRepository.deleteAll();
        admin = saveUser("admin@seu.edu.bd", Role.ADMIN, true);
        alice = saveUser("alice@seu.edu.bd", Role.STUDENT, true);
        adminToken = jwtService.generateAccessToken(UserPrincipal.from(admin));
        aliceToken = jwtService.generateAccessToken(UserPrincipal.from(alice));
    }

    // ---- access control ----

    @Test
    void allAdminRoutes_requireAdmin() throws Exception {
        Listing listing = saveListing(alice);
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                get("/api/admin/users"),
                patch("/api/admin/users/{id}/deactivate", alice.getId()),
                delete("/api/admin/listings/{id}", listing.getId())}) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                get("/api/admin/users"),
                patch("/api/admin/users/{id}/deactivate", alice.getId()),
                delete("/api/admin/listings/{id}", listing.getId())}) {
            mockMvc.perform(request.header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Access denied"));
        }
        assertThat(userRepository.findById(alice.getId()).orElseThrow().isActive()).isTrue();
        assertThat(listingRepository.existsById(listing.getId())).isTrue();
    }

    // ---- GET /api/admin/users ----

    @Test
    void listUsers_returnsPagedUsersWithoutPasswords() throws Exception {
        saveUser("bob@seu.edu.bd", Role.STUDENT, false);

        mockMvc.perform(asAdmin(get("/api/admin/users").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].email").value("bob@seu.edu.bd"))
                .andExpect(jsonPath("$.content[0].enabled").value(false))
                .andExpect(jsonPath("$.content[0].active").value(true))
                .andExpect(jsonPath("$.content[0].role").value("STUDENT"))
                .andExpect(jsonPath("$.content[0].password").doesNotExist());
    }

    @Test
    void listUsers_rejectsOversizedPage() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/users").param("size", "500")))
                .andExpect(status().isBadRequest());
    }

    // ---- PATCH /api/admin/users/{id}/deactivate ----

    @Test
    void deactivate_setsInactive_andIsIdempotent() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", alice.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.getId()))
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", alice.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        User reloaded = userRepository.findById(alice.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
        assertThat(reloaded.isEnabled()).isTrue(); // verification status untouched
    }

    @Test
    void deactivatedUser_isLockedOutEverywhere() throws Exception {
        String refreshToken = jwtService.generateRefreshToken(UserPrincipal.from(alice));
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", alice.getId()))).andExpect(status().isOk());

        // Existing access token stops working immediately.
        mockMvc.perform(get("/api/listings/mine").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account has been deactivated"));
        // Login refused with a clear message, even with the right password.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@seu.edu.bd\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your account has been deactivated"));
        // Refresh refused.
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account has been deactivated"));
    }

    @Test
    void deactivatedUnverifiedUser_cannotReactivateThroughEmailVerification() throws Exception {
        User pending = saveUser("pending@seu.edu.bd", Role.STUDENT, false);
        String code = otpService.issue(pending, OtpPurpose.SIGNUP);
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", pending.getId()))).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/resend-otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pending@seu.edu.bd\"}"))
                .andExpect(status().isOk());
        verify(mailService, never()).sendOtpEmail(any(), any());

        mockMvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pending@seu.edu.bd\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your account has been deactivated"));
        assertThat(userRepository.findById(pending.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void deactivate_self_isRejected() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", admin.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot deactivate your own account"));
        assertThat(userRepository.findById(admin.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void deactivate_missingUser_returns404() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", 999_999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User 999999 not found"));
    }

    // ---- PATCH /api/admin/users/{id}/reactivate ----

    @Test
    void reactivate_restoresAccess_andIsIdempotent() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", alice.getId()))).andExpect(status().isOk());
        mockMvc.perform(login("alice@seu.edu.bd")).andExpect(status().isForbidden());

        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/reactivate", alice.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/reactivate", alice.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(login("alice@seu.edu.bd")).andExpect(status().isOk());
        mockMvc.perform(get("/api/listings/mine").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    @Test
    void reactivate_keepsUnverifiedAccountUnverified() throws Exception {
        User pending = saveUser("pending@seu.edu.bd", Role.STUDENT, false);
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", pending.getId()))).andExpect(status().isOk());

        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/reactivate", pending.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(login("pending@seu.edu.bd"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Please verify your email"));
    }

    @Test
    void reactivate_requiresAdmin_and404sForMissingUser() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/reactivate", alice.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/reactivate", 999_999)))
                .andExpect(status().isNotFound());
    }

    // ---- deactivated owners' listings ----

    @Test
    void deactivatedOwnersListings_areHiddenFromPublic_visibleToAdmin_andReturnOnReactivation() throws Exception {
        Listing aliceListing = saveListing(alice);
        User bob = saveUser("bob@seu.edu.bd", Role.STUDENT, true);
        String bobToken = jwtService.generateAccessToken(UserPrincipal.from(bob));
        saveListing(bob);

        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", alice.getId()))).andExpect(status().isOk());

        // Browse: only Bob's listing remains, for anonymous and logged-in users alike.
        mockMvc.perform(get("/api/listings"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].owner.id").value(bob.getId()));
        mockMvc.perform(get("/api/listings").header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.totalElements").value(1));
        // Direct link: 404 for everyone but admins.
        mockMvc.perform(get("/api/listings/{id}", aliceListing.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Listing " + aliceListing.getId() + " not found"));
        mockMvc.perform(get("/api/listings/{id}", aliceListing.getId()).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(asAdmin(get("/api/listings/{id}", aliceListing.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.id").value(alice.getId()));

        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/reactivate", alice.getId()))).andExpect(status().isOk());

        mockMvc.perform(get("/api/listings")).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/listings/{id}", aliceListing.getId())).andExpect(status().isOk());
    }

    @Test
    void adminCanStillDeleteDeactivatedOwnersListing() throws Exception {
        Listing aliceListing = saveListing(alice);
        mockMvc.perform(asAdmin(patch("/api/admin/users/{id}/deactivate", alice.getId()))).andExpect(status().isOk());

        mockMvc.perform(asAdmin(delete("/api/admin/listings/{id}", aliceListing.getId())))
                .andExpect(status().isNoContent());
        assertThat(listingRepository.existsById(aliceListing.getId())).isFalse();
    }

    // ---- DELETE /api/admin/listings/{id} ----

    @Test
    void deleteListing_removesAnyUsersListing() throws Exception {
        Listing listing = saveListing(alice);

        mockMvc.perform(asAdmin(delete("/api/admin/listings/{id}", listing.getId())))
                .andExpect(status().isNoContent());
        assertThat(listingRepository.existsById(listing.getId())).isFalse();
    }

    @Test
    void deleteListing_missing_returns404() throws Exception {
        mockMvc.perform(asAdmin(delete("/api/admin/listings/{id}", 999_999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Listing 999999 not found"));
    }

    private static MockHttpServletRequestBuilder login(String email) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private User saveUser(String email, Role role, boolean enabled) {
        return userRepository.save(User.builder()
                .fullName(email.substring(0, email.indexOf('@')))
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .role(role)
                .enabled(enabled)
                .build());
    }

    private Listing saveListing(User owner) {
        return listingRepository.save(Listing.builder()
                .title("Lamp").price(new BigDecimal("100")).category(Category.OTHER).owner(owner).build());
    }
}
