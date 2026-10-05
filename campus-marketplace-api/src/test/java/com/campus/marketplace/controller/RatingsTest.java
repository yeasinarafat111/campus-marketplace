package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.RatingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RatingsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderRepository orderRepository;
    @Autowired private RatingRepository ratingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private User seller;
    private User buyer;
    private User other;
    private String sellerToken;
    private String buyerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        ratingRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();
        seller = user("seller@seu.edu.bd", "Sally Seller");
        buyer = user("buyer@seu.edu.bd", "Bob Buyer");
        other = user("other@seu.edu.bd", "Olga Other");
        sellerToken = token(seller);
        buyerToken = token(buyer);
        otherToken = token(other);
    }

    private User user(String email, String name) {
        return userRepository.save(User.builder().fullName(name).email(email).password("x").role(Role.STUDENT).enabled(true).build());
    }

    private String token(User u) {
        return jwtService.generateAccessToken(UserPrincipal.from(u));
    }

    private Order order(OrderStatus status) {
        return orderRepository.save(Order.builder().listingTitle("Desk lamp").buyer(buyer).seller(seller)
                .itemPriceCents(1200L).depositCents(50L).balanceCents(1150L).currency("usd").status(status).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder rate(Long orderId, String token, String body) {
        return as(post("/api/orders/{id}/rating", orderId), token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void theBuyer_ratesACompletedOrder_once() throws Exception {
        Order o = order(OrderStatus.COMPLETED);
        mockMvc.perform(as(get("/api/orders/purchases"), buyerToken))
                .andExpect(jsonPath("$.content[0].canRate").value(true)).andExpect(jsonPath("$.content[0].rating").isEmpty());

        mockMvc.perform(rate(o.getId(), buyerToken, "{\"stars\":4,\"comment\":\"  Quick and friendly  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canRate").value(false))
                .andExpect(jsonPath("$.rating.stars").value(4))
                .andExpect(jsonPath("$.rating.comment").value("Quick and friendly"));
        mockMvc.perform(rate(o.getId(), buyerToken, "{\"stars\":5}")).andExpect(status().isConflict());
        assertThat(ratingRepository.count()).isEqualTo(1);

        mockMvc.perform(as(get("/api/orders/purchases"), buyerToken))
                .andExpect(jsonPath("$.content[0].canRate").value(false)).andExpect(jsonPath("$.content[0].rating.stars").value(4));
        mockMvc.perform(as(get("/api/orders/sales"), sellerToken))
                .andExpect(jsonPath("$.content[0].canRate").value(false)).andExpect(jsonPath("$.content[0].rating.stars").value(4));
    }

    @Test
    void onlyTheBuyer_andOnlyAfterCompletion() throws Exception {
        Order paid = order(OrderStatus.PAID);
        mockMvc.perform(rate(paid.getId(), buyerToken, "{\"stars\":5}")).andExpect(status().isConflict());
        Order done = order(OrderStatus.COMPLETED);
        mockMvc.perform(rate(done.getId(), sellerToken, "{\"stars\":5}")).andExpect(status().isForbidden());
        mockMvc.perform(rate(done.getId(), otherToken, "{\"stars\":5}")).andExpect(status().isForbidden());
        mockMvc.perform(rate(999999L, buyerToken, "{\"stars\":5}")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/orders/{id}/rating", done.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"stars\":5}"))
                .andExpect(status().isUnauthorized());
        assertThat(ratingRepository.count()).isZero();
        mockMvc.perform(as(get("/api/orders/purchases"), buyerToken)).andExpect(jsonPath("$.content[?(@.status=='PAID')].canRate").value(false));
    }

    @Test
    void starsAreValidated_commentLengthToo() throws Exception {
        Order o = order(OrderStatus.COMPLETED);
        for (String body : new String[] {"{}", "{\"stars\":0}", "{\"stars\":6}", "{\"stars\":-1}", "{\"stars\":\"many\"}",
                "{\"stars\":3,\"comment\":\"" + "x".repeat(501) + "\"}"}) {
            mockMvc.perform(rate(o.getId(), buyerToken, body)).andExpect(status().isBadRequest());
        }
        assertThat(ratingRepository.count()).isZero();
        mockMvc.perform(rate(o.getId(), buyerToken, "{\"stars\":1}")).andExpect(status().isOk());
    }

    @Test
    void theProfile_showsTheAverageAndCount_andReviewsShowFirstNamesOnly() throws Exception {
        mockMvc.perform(get("/api/users/{id}/profile", seller.getId()))
                .andExpect(jsonPath("$.averageRating").isEmpty()).andExpect(jsonPath("$.ratingCount").value(0));

        mockMvc.perform(rate(order(OrderStatus.COMPLETED).getId(), buyerToken, "{\"stars\":5,\"comment\":\"Great\"}")).andExpect(status().isOk());
        mockMvc.perform(rate(order(OrderStatus.COMPLETED).getId(), buyerToken, "{\"stars\":4}")).andExpect(status().isOk());
        mockMvc.perform(rate(order(OrderStatus.COMPLETED).getId(), buyerToken, "{\"stars\":4}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/users/{id}/profile", seller.getId()))
                .andExpect(jsonPath("$.averageRating").value(4.3)).andExpect(jsonPath("$.ratingCount").value(3));
        mockMvc.perform(get("/api/users/{id}/ratings", seller.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].reviewer").value("Bob"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.content[2].comment").value("Great"));
        mockMvc.perform(get("/api/users/{id}/ratings", 999999)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/users/{id}/ratings", seller.getId()).param("size", "500")).andExpect(status().isBadRequest());
    }
}
