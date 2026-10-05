package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SellerProfileTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ListingRepository listingRepository;
    @Autowired private UserRepository userRepository;

    private User seller;

    @BeforeEach
    void setUp() {
        listingRepository.deleteAll();
        userRepository.deleteAll();
        seller = userRepository.save(User.builder().fullName("Sam Seller").email("sam@seu.edu.bd").phone("+8801712345678")
                .password("x").role(Role.STUDENT).enabled(true).build());
    }

    private void listing(ListingStatus status) {
        listingRepository.save(Listing.builder().title("Item").price(new BigDecimal("10.00"))
                .category(Category.OTHER).status(status).owner(seller).build());
    }

    @Test
    void publicProfile_showsCounts_andNeverContactDetails() throws Exception {
        listing(ListingStatus.ACTIVE);
        listing(ListingStatus.ACTIVE);
        listing(ListingStatus.SOLD);
        listing(ListingStatus.PENDING_PAYMENT);

        mockMvc.perform(get("/api/users/{id}/profile", seller.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Sam Seller"))
                .andExpect(jsonPath("$.memberSince").exists())
                .andExpect(jsonPath("$.activeListings").value(2))
                .andExpect(jsonPath("$.itemsSold").value(1))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void unknown_deactivated_andUnverifiedUsers_are404() throws Exception {
        mockMvc.perform(get("/api/users/{id}/profile", 999999)).andExpect(status().isNotFound());
        seller.setActive(false);
        userRepository.save(seller);
        mockMvc.perform(get("/api/users/{id}/profile", seller.getId())).andExpect(status().isNotFound());
        seller.setActive(true);
        seller.setEnabled(false);
        userRepository.save(seller);
        mockMvc.perform(get("/api/users/{id}/profile", seller.getId())).andExpect(status().isNotFound());
    }

    @Test
    void openingTheProfileRoute_doesNotOpenMe() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }
}
