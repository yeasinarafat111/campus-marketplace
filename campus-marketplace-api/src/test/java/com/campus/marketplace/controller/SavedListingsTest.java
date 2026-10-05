package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.SavedListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SavedListingsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ListingRepository listingRepository;
    @Autowired private SavedListingRepository savedRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        savedRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
        alice = user("alice@seu.edu.bd", "Alice");
        bob = user("bob@seu.edu.bd", "Bob");
        aliceToken = jwtService.generateAccessToken(UserPrincipal.from(alice));
        bobToken = jwtService.generateAccessToken(UserPrincipal.from(bob));
    }

    private User user(String email, String name) {
        return userRepository.save(User.builder().fullName(name).email(email).password("x").role(Role.STUDENT).enabled(true).build());
    }

    private Listing listing(String title, ListingStatus status) {
        return listingRepository.save(Listing.builder().title(title).price(new BigDecimal("10.00"))
                .category(Category.OTHER).status(status).owner(bob).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.header("Authorization", "Bearer " + token);
    }

    @Test
    void saving_isIdempotent_andListedNewestFirst() throws Exception {
        Listing a = listing("Lamp", ListingStatus.ACTIVE);
        Listing b = listing("Desk", ListingStatus.ACTIVE);
        mockMvc.perform(as(post("/api/listings/{id}/save", a.getId()), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(post("/api/listings/{id}/save", a.getId()), aliceToken)).andExpect(status().isNoContent());
        Thread.sleep(5);
        mockMvc.perform(as(post("/api/listings/{id}/save", b.getId()), aliceToken)).andExpect(status().isNoContent());
        assertThat(savedRepository.count()).isEqualTo(2);

        mockMvc.perform(as(get("/api/users/me/saved"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Desk"))
                .andExpect(jsonPath("$.content[1].title").value("Lamp"))
                .andExpect(jsonPath("$.content[0].owner.email").doesNotExist());
        mockMvc.perform(as(get("/api/users/me/saved/ids"), aliceToken))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unsaving_removes_andIsIdempotent() throws Exception {
        Listing a = listing("Lamp", ListingStatus.ACTIVE);
        mockMvc.perform(as(post("/api/listings/{id}/save", a.getId()), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/api/listings/{id}/save", a.getId()), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/api/listings/{id}/save", a.getId()), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/users/me/saved"), aliceToken)).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void savedListsArePerUser() throws Exception {
        Listing a = listing("Lamp", ListingStatus.ACTIVE);
        mockMvc.perform(as(post("/api/listings/{id}/save", a.getId()), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/users/me/saved"), bobToken)).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(as(delete("/api/listings/{id}/save", a.getId()), bobToken)).andExpect(status().isNoContent());
        assertThat(savedRepository.count()).isEqualTo(1);
    }

    @Test
    void unknownAndUnpublishedListings_cannotBeSaved() throws Exception {
        Listing pending = listing("Pending", ListingStatus.PENDING_PAYMENT);
        mockMvc.perform(as(post("/api/listings/{id}/save", 999999), aliceToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/listings/{id}/save", pending.getId()), aliceToken)).andExpect(status().isNotFound());
    }

    @Test
    void aSoldListingStaysSaved_butOneThatBecomesPendingIsHidden() throws Exception {
        Listing a = listing("Lamp", ListingStatus.ACTIVE);
        mockMvc.perform(as(post("/api/listings/{id}/save", a.getId()), aliceToken)).andExpect(status().isNoContent());
        a.setStatus(ListingStatus.SOLD);
        listingRepository.save(a);
        mockMvc.perform(as(get("/api/users/me/saved"), aliceToken))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].status").value("SOLD"));
        a.setStatus(ListingStatus.PENDING_PAYMENT);
        listingRepository.save(a);
        mockMvc.perform(as(get("/api/users/me/saved"), aliceToken)).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void deletingTheListing_removesTheSave() throws Exception {
        Listing a = listing("Lamp", ListingStatus.ACTIVE);
        mockMvc.perform(as(post("/api/listings/{id}/save", a.getId()), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/api/listings/{id}", a.getId()), bobToken)).andExpect(status().isNoContent());
        assertThat(savedRepository.count()).isZero();
    }

    @Test
    void requiresLogin_andValidatesPaging() throws Exception {
        Listing a = listing("Lamp", ListingStatus.ACTIVE);
        mockMvc.perform(post("/api/listings/{id}/save", a.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/saved")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(get("/api/users/me/saved").param("size", "500"), aliceToken)).andExpect(status().isBadRequest());
    }
}
