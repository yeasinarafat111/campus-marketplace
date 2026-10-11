package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.ItemCondition;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared fixtures for the assistant API tests. */
final class AssistantTestSupport {

    static final String SELLER_NAME = "Rahim Uddin";
    static final String SELLER_EMAIL = "rahim.seller@seu.edu.bd";
    static final String SELLER_PHONE = "+8801712345678";
    static final String BUYER_NAME = "Karim Buyer";
    static final String BUYER_EMAIL = "karim.buyer@seu.edu.bd";

    private AssistantTestSupport() {
    }

    static User seller(UserRepository users) {
        return users.save(User.builder().fullName(SELLER_NAME).email(SELLER_EMAIL).phone(SELLER_PHONE).password("x")
                .role(Role.STUDENT).enabled(true).build());
    }

    static User buyer(UserRepository users) {
        return users.save(User.builder().fullName(BUYER_NAME).email(BUYER_EMAIL).phone("+8801898765432").password("x")
                .role(Role.STUDENT).enabled(true).build());
    }

    static Listing listing(ListingRepository listings, User owner, String title, String description, String price) {
        return listings.save(Listing.builder().title(title).description(description).price(new BigDecimal(price))
                .category(Category.ELECTRONICS).itemCondition(ItemCondition.GOOD).status(ListingStatus.ACTIVE)
                .owner(owner).build());
    }

    static String token(JwtService jwt, User user) {
        return jwt.generateAccessToken(UserPrincipal.from(user));
    }

    static MockHttpServletRequestBuilder ask(Long listingId, String token, String json) {
        MockHttpServletRequestBuilder r = post("/api/listings/{id}/assistant", listingId)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return token == null ? r : r.header("Authorization", "Bearer " + token);
    }
}
