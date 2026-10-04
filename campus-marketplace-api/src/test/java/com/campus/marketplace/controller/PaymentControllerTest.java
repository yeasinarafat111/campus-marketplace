package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.PaymentProviderException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.CheckoutGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerTest {

    private static final String SESSION_URL = "https://checkout.stripe.com/c/pay/cs_test_123";

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @MockitoBean private CheckoutGateway checkoutGateway;

    private User seller;
    private User buyer;
    private Listing listing;

    @BeforeEach
    void setUp() {
        cleanUp();
        seller = saveUser("seller@seu.edu.bd");
        buyer = saveUser("buyer@seu.edu.bd");
        listing = saveListing("Calculus Textbook", ListingStatus.ACTIVE, seller);
        when(checkoutGateway.createCheckoutSession(any()))
                .thenReturn(new CheckoutGateway.CheckoutSession("cs_test_123", SESSION_URL));
    }

    @AfterEach
    void cleanUp() {
        // Payments block user deletion by design, so remove them first.
        paymentRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void featureListing_byOwner_createsSessionAndPendingPayment() throws Exception {
        checkout(seller, listing.getId(), "FEATURE_LISTING")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(SESSION_URL))
                .andExpect(jsonPath("$.sessionId").value("cs_test_123"))
                .andExpect(jsonPath("$.amount").value(199))
                .andExpect(jsonPath("$.currency").value("usd"))
                .andExpect(jsonPath("$.purpose").value("FEATURE_LISTING"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentId").isNumber());

        Payment payment = paymentRepository.findByStripeSessionId("cs_test_123").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAmount()).isEqualTo(199L);
        assertThat(payment.getCurrency()).isEqualTo("usd");
        assertThat(payment.getPurpose()).isEqualTo(PaymentPurpose.FEATURE_LISTING);
        assertThat(payment.getCreatedAt()).isNotNull();

        ArgumentCaptor<CheckoutGateway.CheckoutRequest> sent = ArgumentCaptor.forClass(CheckoutGateway.CheckoutRequest.class);
        verify(checkoutGateway).createCheckoutSession(sent.capture());
        assertThat(sent.getValue().amount()).isEqualTo(199L);
        assertThat(sent.getValue().productName()).isEqualTo("Feature listing: Calculus Textbook");
        assertThat(sent.getValue().customerEmail()).isEqualTo("seller@seu.edu.bd");
        assertThat(sent.getValue().metadata())
                .containsEntry("user_id", seller.getId().toString())
                .containsEntry("listing_id", listing.getId().toString())
                .containsEntry("purpose", "FEATURE_LISTING");
    }

    @Test
    void reserveItem_byBuyer_usesDepositAmount() throws Exception {
        checkout(buyer, listing.getId(), "RESERVE_ITEM")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(500))
                .andExpect(jsonPath("$.purpose").value("RESERVE_ITEM"));

        Payment payment = paymentRepository.findByStripeSessionId("cs_test_123").orElseThrow();
        assertThat(payment.getAmount()).isEqualTo(500L);
        assertThat(paymentRepository.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING));
    }

    @Test
    void clientCannotChooseTheAmount() throws Exception {
        mockMvc.perform(post("/api/payments/checkout-session").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token(buyer))
                        .content("{\"listingId\":" + listing.getId() + ",\"purpose\":\"RESERVE_ITEM\",\"amount\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(500));
    }

    @Test
    void featureListing_byNonOwner_returns403() throws Exception {
        checkout(buyer, listing.getId(), "FEATURE_LISTING")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only the listing owner can feature a listing"));
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void reserveItem_ownListing_returns400() throws Exception {
        checkout(seller, listing.getId(), "RESERVE_ITEM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't reserve your own listing"));
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void soldListing_returns409() throws Exception {
        Listing sold = saveListing("Desk", ListingStatus.SOLD, seller);

        checkout(buyer, sold.getId(), "RESERVE_ITEM")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This listing is no longer available"));
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void alreadyReservedListing_refusesNewReservationCheckout() throws Exception {
        listing.setReservedBy(buyer);
        listing.setReservedAt(java.time.Instant.now());
        listingRepository.save(listing);
        User another = saveUser("another@seu.edu.bd");

        checkout(another, listing.getId(), "RESERVE_ITEM")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This listing is already reserved"));
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void alreadyFeaturedListing_refusesNewFeatureCheckout_untilItExpires() throws Exception {
        listing.setFeaturedUntil(java.time.Instant.now().plus(java.time.Duration.ofDays(3)));
        listingRepository.save(listing);

        checkout(seller, listing.getId(), "FEATURE_LISTING")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This listing is already featured"));
        assertNoStripeCallAndNoPayment();

        listing.setFeaturedUntil(java.time.Instant.now().minus(java.time.Duration.ofMinutes(1)));
        listingRepository.save(listing);
        checkout(seller, listing.getId(), "FEATURE_LISTING").andExpect(status().isCreated());
    }

    @Test
    void missingListing_returns404() throws Exception {
        checkout(buyer, 999_999L, "RESERVE_ITEM").andExpect(status().isNotFound());
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void deactivatedOwnersListing_returns404() throws Exception {
        seller.setActive(false);
        userRepository.save(seller);

        checkout(buyer, listing.getId(), "RESERVE_ITEM").andExpect(status().isNotFound());
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/payments/checkout-session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":" + listing.getId() + ",\"purpose\":\"RESERVE_ITEM\"}"))
                .andExpect(status().isUnauthorized());
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void validatesBody() throws Exception {
        mockMvc.perform(post("/api/payments/checkout-session").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token(buyer)).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.listingId").value("listingId is required"))
                .andExpect(jsonPath("$.fieldErrors.purpose").value("purpose is required"));
        checkout(buyer, listing.getId(), "DONATE").andExpect(status().isBadRequest());
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void stripeFailure_returns502_andSavesNothing() throws Exception {
        when(checkoutGateway.createCheckoutSession(any())).thenThrow(
                new PaymentProviderException("Payment provider error. Please try again.", HttpStatus.BAD_GATEWAY, null));

        checkout(buyer, listing.getId(), "RESERVE_ITEM")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Payment provider error. Please try again."));
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void deletingListing_keepsPaymentRecord() throws Exception {
        checkout(seller, listing.getId(), "FEATURE_LISTING").andExpect(status().isCreated());

        listingRepository.deleteById(listing.getId());

        Payment payment = paymentRepository.findByStripeSessionId("cs_test_123").orElseThrow();
        assertThat(paymentRepository.findAll()).hasSize(1);
        assertThat(payment.getAmount()).isEqualTo(199L);
    }

    private void assertNoStripeCallAndNoPayment() {
        verify(checkoutGateway, never()).createCheckoutSession(any());
        assertThat(paymentRepository.count()).isZero();
    }

    private ResultActions checkout(User user, Long listingId, String purpose) throws Exception {
        return mockMvc.perform(post("/api/payments/checkout-session").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token(user))
                .content("{\"listingId\":" + listingId + ",\"purpose\":\"" + purpose + "\"}"));
    }

    private String token(User user) {
        return jwtService.generateAccessToken(UserPrincipal.from(user));
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .fullName(email.substring(0, email.indexOf('@'))).email(email).password("x")
                .role(Role.STUDENT).enabled(true).build());
    }

    private Listing saveListing(String title, ListingStatus status, User owner) {
        return listingRepository.save(Listing.builder()
                .title(title).price(new BigDecimal("450")).category(Category.BOOKS).status(status).owner(owner).build());
    }
}
