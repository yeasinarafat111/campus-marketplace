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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        // cs_test_123 for the first checkout in a test, then 124, 125, ... (session ids are unique).
        var nextSession = new java.util.concurrent.atomic.AtomicInteger(123);
        when(checkoutGateway.createCheckoutSession(any()))
                .thenAnswer(inv -> new CheckoutGateway.CheckoutSession("cs_test_" + nextSession.getAndIncrement(), SESSION_URL));
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
                .andExpect(jsonPath("$.amount").value(2250))
                .andExpect(jsonPath("$.currency").value("usd"))
                .andExpect(jsonPath("$.purpose").value("FEATURE_LISTING"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentId").isNumber());

        Payment payment = paymentRepository.findByStripeSessionId("cs_test_123").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAmount()).isEqualTo(2250L);
        assertThat(payment.getCurrency()).isEqualTo("usd");
        assertThat(payment.getPurpose()).isEqualTo(PaymentPurpose.FEATURE_LISTING);
        assertThat(payment.getCreatedAt()).isNotNull();

        ArgumentCaptor<CheckoutGateway.CheckoutRequest> sent = ArgumentCaptor.forClass(CheckoutGateway.CheckoutRequest.class);
        verify(checkoutGateway).createCheckoutSession(sent.capture());
        assertThat(sent.getValue().amount()).isEqualTo(2250L);
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
                .andExpect(jsonPath("$.amount").value(2250))
                .andExpect(jsonPath("$.purpose").value("RESERVE_ITEM"));

        Payment payment = paymentRepository.findByStripeSessionId("cs_test_123").orElseThrow();
        assertThat(payment.getAmount()).isEqualTo(2250L);
        assertThat(paymentRepository.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING));
    }

    // ---- fees computed from the price (decisions D1/D2/D7) ----

    @Test
    void checkout_chargesTheFeeComputedFromThePrice_andRecordsTheBasis() throws Exception {
        // $12.00 -> 3% = $0.36, raised to the $0.50 minimum.
        Listing twelve = saveListing("Lamp", "12.00", ListingStatus.ACTIVE, seller);
        checkout(buyer, twelve.getId(), "RESERVE_ITEM")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(50));
        Payment payment = paymentRepository.findByStripeSessionId("cs_test_123").orElseThrow();
        assertThat(payment.getAmount()).isEqualTo(50L);
        assertThat(payment.getBasisPriceCents()).isEqualTo(1200L);
        assertThat(payment.getRateBasisPoints()).isEqualTo(300);
    }

    @Test
    void checkout_featureAndReserveUseTheSameFormula() throws Exception {
        // $25.00 -> 5% = $1.25 for both purposes.
        Listing twentyFive = saveListing("Chair", "25.00", ListingStatus.ACTIVE, seller);
        checkout(seller, twentyFive.getId(), "FEATURE_LISTING")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(125));
        checkout(buyer, twentyFive.getId(), "RESERVE_ITEM")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(125));
        assertThat(paymentRepository.findAll()).allSatisfy(p -> {
            assertThat(p.getBasisPriceCents()).isEqualTo(2500L);
            assertThat(p.getRateBasisPoints()).isEqualTo(500);
        });
    }

    @Test
    void checkout_atTheThreshold_usesTheHighRate() throws Exception {
        checkout(buyer, saveListing("A", "19.99", ListingStatus.ACTIVE, seller).getId(), "RESERVE_ITEM")
                .andExpect(jsonPath("$.amount").value(60));
        checkout(buyer, saveListing("B", "20.00", ListingStatus.ACTIVE, seller).getId(), "RESERVE_ITEM")
                .andExpect(jsonPath("$.amount").value(100));
    }

    @Test
    void checkout_forFreeOrSubDollarListings_isRejectedBeforeStripe() throws Exception {
        Listing free = saveListing("Free chair", "0.00", ListingStatus.ACTIVE, seller);
        checkout(buyer, free.getId(), "RESERVE_ITEM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Free items can't be featured or reserved. Message the owner instead."));
        checkout(seller, free.getId(), "FEATURE_LISTING").andExpect(status().isBadRequest());

        Listing cheap = saveListing("Legacy sticker", "0.60", ListingStatus.ACTIVE, seller);
        checkout(buyer, cheap.getId(), "RESERVE_ITEM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Items under $1.00")));
        assertNoStripeCallAndNoPayment();
    }

    @Test
    void checkout_atTheMinimumPaidPrice_leavesAChargeableBalance() throws Exception {
        // $1.00: deposit is the $0.50 minimum, so the balance at Buy is $0.50, Stripe's own minimum.
        Listing dollar = saveListing("Pen", "1.00", ListingStatus.ACTIVE, seller);
        checkout(buyer, dollar.getId(), "RESERVE_ITEM")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(50));
        fees(buyer, dollar.getId()).andExpect(jsonPath("$.purchaseCents").value(50));
    }

    @Test
    void feeQuote_for12DollarListing() throws Exception {
        Listing twelve = saveListing("Lamp", "12.00", ListingStatus.ACTIVE, seller);
        fees(buyer, twelve.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureCents").value(50))
                .andExpect(jsonPath("$.reserveCents").value(50))
                .andExpect(jsonPath("$.purchaseCents").value(1150))
                .andExpect(jsonPath("$.currency").value("usd"))
                .andExpect(jsonPath("$.rate").value(0.03))
                .andExpect(jsonPath("$.minimumCents").value(50))
                .andExpect(jsonPath("$.basis").value("12.00"))
                .andExpect(jsonPath("$.featureDays").value(7))
                .andExpect(jsonPath("$.reservationHours").value(48));
    }

    @Test
    void feeQuote_for25DollarListing_andForTheOwnerToo() throws Exception {
        Listing listing25 = saveListing("Chair", "25.00", ListingStatus.ACTIVE, seller);
        for (User viewer : new User[] {buyer, seller}) {
            fees(viewer, listing25.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.featureCents").value(125))
                    .andExpect(jsonPath("$.reserveCents").value(125))
                    .andExpect(jsonPath("$.purchaseCents").value(2375))
                    .andExpect(jsonPath("$.rate").value(0.05))
                    .andExpect(jsonPath("$.basis").value("25.00"));
        }
    }

    @Test
    void feeQuote_matchesWhatCheckoutCharges() throws Exception {
        for (String price : new String[] {"1.00", "12.00", "19.99", "20.00", "30.10", "150.00", "999.99"}) {
            Listing l = saveListing("Item " + price, price, ListingStatus.ACTIVE, seller);
            long quoted = readLong(fees(buyer, l.getId()).andExpect(status().isOk()), "reserveCents");
            long charged = readLong(checkout(buyer, l.getId(), "RESERVE_ITEM").andExpect(status().isCreated()), "amount");
            assertThat(charged).as("price " + price).isEqualTo(quoted);
        }
    }

    @Test
    void feeQuote_requiresLogin() throws Exception {
        mockMvc.perform(get("/api/listings/" + listing.getId() + "/fees")).andExpect(status().isUnauthorized());
    }

    @Test
    void feeQuote_rejectsFreeSoldAndUnknownListings() throws Exception {
        fees(buyer, saveListing("Free", "0", ListingStatus.ACTIVE, seller).getId())
                .andExpect(status().isBadRequest());
        fees(buyer, saveListing("Gone", "10", ListingStatus.SOLD, seller).getId())
                .andExpect(status().isConflict());
        fees(buyer, 999_999L).andExpect(status().isNotFound());
    }

    @Test
    void feeQuote_forDeactivatedSeller_returns404() throws Exception {
        seller.setActive(false);
        userRepository.save(seller);
        fees(buyer, listing.getId()).andExpect(status().isNotFound());
    }

    @Test
    void clientCannotChooseTheAmount() throws Exception {
        mockMvc.perform(post("/api/payments/checkout-session").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token(buyer))
                        .content("{\"listingId\":" + listing.getId() + ",\"purpose\":\"RESERVE_ITEM\",\"amount\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(2250));
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
        assertThat(payment.getAmount()).isEqualTo(2250L);
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

    private long readLong(ResultActions result, String field) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.andReturn().getResponse().getContentAsString()).get(field).asLong();
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
        return saveListing(title, "450", status, owner);
    }

    private Listing saveListing(String title, String price, ListingStatus status, User owner) {
        return listingRepository.save(Listing.builder()
                .title(title).price(new BigDecimal(price)).category(Category.BOOKS).status(status).owner(owner).build());
    }

    private ResultActions fees(User user, Long listingId) throws Exception {
        return mockMvc.perform(get("/api/listings/" + listingId + "/fees")
                .header("Authorization", "Bearer " + token(user)));
    }
}
