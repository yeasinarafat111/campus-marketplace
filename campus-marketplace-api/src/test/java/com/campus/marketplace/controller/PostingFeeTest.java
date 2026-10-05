package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.CheckoutGateway;
import com.campus.marketplace.service.ListingService;
import com.campus.marketplace.service.MailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The posting fee for listings priced above $5.00: pending until paid, hidden meanwhile, and the price-raise block. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.payments.posting-fee.enabled=true")
class PostingFeeTest {

    private static final String SECRET = "whsec_test_secret"; // matches src/test/resources/application.yml

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private ListingService listingService;
    @MockitoBean private CheckoutGateway checkoutGateway;
    @MockitoBean private MailService mailService;

    private User seller;
    private User other;
    private User admin;
    private final AtomicInteger sessions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        cleanUp();
        sessions.set(0);
        seller = saveUser("seller@seu.edu.bd", "Sally Seller", Role.STUDENT);
        other = saveUser("other@seu.edu.bd", "Olga Other", Role.STUDENT);
        admin = saveUser("admin@seu.edu.bd", "Ada Admin", Role.ADMIN);
        when(checkoutGateway.createCheckoutSession(any())).thenAnswer(inv ->
                new CheckoutGateway.CheckoutSession("cs_" + sessions.incrementAndGet(), "https://checkout.stripe.com/x"));
    }

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
        paymentRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- who pays: the $5.00 boundary ----

    @Test
    void aListingAtOrBelow5Dollars_postsForFree_includingFree() throws Exception {
        for (String price : new String[] {"0", "1.00", "4.99", "5", "5.00"}) {
            create(seller, price).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));
        }
        assertThat(listingRepository.findAll()).allMatch(l -> l.getStatus() == ListingStatus.ACTIVE);
        // Nothing was charged and nothing is owed.
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void aListingAbove5Dollars_waitsForItsPostingFee() throws Exception {
        for (String price : new String[] {"5.01", "12.00", "450.50"}) {
            create(seller, price).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        }
        assertThat(listingRepository.findAll()).allMatch(l -> l.getStatus() == ListingStatus.PENDING_PAYMENT);
    }

    @Test
    void theQuote_followsTheSameFormulaAsEveryOtherFee() throws Exception {
        quote("5.00").andExpect(jsonPath("$.applies").value(false)).andExpect(jsonPath("$.feeCents").value(0))
                .andExpect(jsonPath("$.thresholdCents").value(500));
        quote("0").andExpect(jsonPath("$.applies").value(false));
        quote("5.01").andExpect(jsonPath("$.applies").value(true)).andExpect(jsonPath("$.feeCents").value(50))   // 3% = 0.15, minimum 0.50
                .andExpect(jsonPath("$.rate").value(0.03)).andExpect(jsonPath("$.currency").value("usd"));
        quote("19.99").andExpect(jsonPath("$.feeCents").value(60));
        quote("20.00").andExpect(jsonPath("$.feeCents").value(100)).andExpect(jsonPath("$.rate").value(0.05));
        quote("150").andExpect(jsonPath("$.feeCents").value(750));
        mockMvc.perform(get("/api/listings/posting-fee").param("price", "abc")).andExpect(status().isBadRequest());
    }

    // ---- paying ----

    @Test
    void checkout_chargesThePostingFee_andRecordsWhatItWasWorkedOutFrom() throws Exception {
        long id = createdId(create(seller, "25.00"));

        checkout(seller, id).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(125))
                .andExpect(jsonPath("$.purpose").value("POSTING_FEE"));

        Payment payment = paymentRepository.findAll().get(0);
        assertThat(payment.getPurpose()).isEqualTo(PaymentPurpose.POSTING_FEE);
        assertThat(payment.getBasisPriceCents()).isEqualTo(2500L);
        assertThat(payment.getRateBasisPoints()).isEqualTo(500);
    }

    @Test
    void theWebhookPublishesTheListing_andRemembersWhatTheFeeCovered() throws Exception {
        long id = createdId(create(seller, "12.00"));
        String session = checkoutSessionId(seller, id);

        completePayment(session);

        Listing live = reload(id);
        assertThat(live.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(live.getPostingFeeBasisCents()).isEqualTo(1200L);
        assertThat(paymentRepository.findByStripeSessionId(session).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        fetch(null, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void theWebhookIsIdempotent_aReplayChangesNothingAndRefundsNothing() throws Exception {
        long id = createdId(create(seller, "12.00"));
        String session = checkoutSessionId(seller, id);
        completePayment(session);
        // The owner lowers the price afterwards; a replay must not touch it.
        mockMvc.perform(as(put("/api/listings/{id}", id), seller).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Lamp\",\"price\":9,\"category\":\"OTHER\"}")).andExpect(status().isOk());

        completePayment(session);
        completePayment(session);

        assertThat(reload(id).getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(reload(id).getPostingFeeBasisCents()).isEqualTo(1200L);
        verify(checkoutGateway, never()).refund(anyString(), anyString());
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void aSecondCheckoutForTheSameListing_isRefundedAutomatically() throws Exception {
        long id = createdId(create(seller, "12.00"));
        String first = checkoutSessionId(seller, id);
        String second = checkoutSessionId(seller, id); // opened it in two tabs

        completePayment(first);
        completePayment(second);

        assertThat(paymentRepository.findByStripeSessionId(first).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentRepository.findByStripeSessionId(second).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(checkoutGateway, times(1)).refund(eq("pi_" + second), anyString());
    }

    @Test
    void ifThePriceWasRaisedAfterCheckoutStarted_theFeeIsRefunded_andTheListingStaysUnpublished() throws Exception {
        long id = createdId(create(seller, "12.00"));
        String session = checkoutSessionId(seller, id);
        mockMvc.perform(as(put("/api/listings/{id}", id), seller).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Lamp\",\"price\":200,\"category\":\"OTHER\"}")).andExpect(status().isOk());

        completePayment(session);

        assertThat(reload(id).getStatus()).isEqualTo(ListingStatus.PENDING_PAYMENT);
        assertThat(paymentRepository.findByStripeSessionId(session).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(checkoutGateway).refund(eq("pi_" + session), anyString());
    }

    @Test
    void aFeePaidForAListingThatNoLongerExists_isRefunded() throws Exception {
        long id = createdId(create(seller, "12.00"));
        String session = checkoutSessionId(seller, id);
        mockMvc.perform(as(delete("/api/listings/{id}", id), seller)).andExpect(status().isNoContent());

        completePayment(session);

        assertThat(paymentRepository.findByStripeSessionId(session).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void onlyTheOwnerCanPay_andOnlyForAListingThatIsWaiting() throws Exception {
        long id = createdId(create(seller, "12.00"));
        checkout(other, id).andExpect(status().isNotFound());   // other people can't even see it
        checkout(admin, id).andExpect(status().isNotFound()); // hidden from every non-owner, admins included
        mockMvc.perform(post("/api/payments/checkout-session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":" + id + ",\"purpose\":\"POSTING_FEE\"}")).andExpect(status().isUnauthorized());
        assertThat(paymentRepository.count()).isZero();

        completePayment(checkoutSessionId(seller, id));
        checkout(seller, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This listing doesn't need a posting fee"));
    }

    @Test
    void loweringThePriceToTheFreeTier_publishesItWithoutPaying() throws Exception {
        long id = createdId(create(seller, "12.00"));

        mockMvc.perform(as(put("/api/listings/{id}", id), seller).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Lamp\",\"price\":4.50,\"category\":\"OTHER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(reload(id).getPostingFeeBasisCents()).isEqualTo(500L);
    }

    // ---- hidden until paid ----

    @Test
    void aPendingListing_isHiddenFromEveryoneButItsOwnerAndAdmins() throws Exception {
        long id = createdId(create(seller, "12.00"));
        create(seller, "3.00").andExpect(status().isCreated()); // a free-to-post one for contrast

        // Search, the featured row and the category counts only know about the published one.
        mockMvc.perform(get("/api/listings")).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/listings").param("ownerId", String.valueOf(seller.getId())))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/listings/category-counts")).andExpect(jsonPath("$.OTHER").value(1));
        mockMvc.perform(get("/api/listings/featured")).andExpect(jsonPath("$.length()").value(0));
        // Direct links: 404 for the public and other students, visible to the owner and admins.
        fetch(null, id).andExpect(status().isNotFound());
        fetch(other, id).andExpect(status().isNotFound());
        fetch(seller, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        fetch(admin, id).andExpect(status().isOk());
        // They can't be reserved, featured, quoted, chatted about or bought.
        mockMvc.perform(as(post("/api/conversations"), other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":" + id + "}")).andExpect(status().isNotFound());
        checkoutPurpose(other, id, "RESERVE_ITEM").andExpect(status().isNotFound());
        checkoutPurpose(seller, id, "FEATURE_LISTING").andExpect(status().isConflict());
        mockMvc.perform(as(get("/api/listings/{id}/fees", id), other)).andExpect(status().isNotFound());
    }

    @Test
    void theOwnersListShowsPendingListings_underTheirOwnFilter() throws Exception {
        create(seller, "12.00").andExpect(status().isCreated());
        create(seller, "3.00").andExpect(status().isCreated());

        mockMvc.perform(as(get("/api/listings/mine").param("status", "PENDING_PAYMENT"), seller))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_PAYMENT"));
        mockMvc.perform(as(get("/api/listings/mine").param("status", "ACTIVE"), seller))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(as(get("/api/listings/mine"), seller)).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(as(get("/api/listings/mine"), other)).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aPendingListing_cantBeMarkedSold_butCanBeDeleted() throws Exception {
        long id = createdId(create(seller, "12.00"));
        mockMvc.perform(as(patch("/api/listings/{id}/sold", id), seller)).andExpect(status().isConflict());
        mockMvc.perform(as(delete("/api/listings/{id}", id), seller)).andExpect(status().isNoContent());
    }

    // ---- the price can't be raised past what the posting covered ----

    @Test
    void afterPublishing_thePriceCanBeLowered_butNotRaisedAboveWhatWasPaidFor() throws Exception {
        long id = createdId(create(seller, "12.00"));
        completePayment(checkoutSessionId(seller, id));

        update(seller, id, "12.50").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("$12.00")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("post it again")));
        assertThat(reload(id).getPrice()).isEqualByComparingTo("12.00");

        update(seller, id, "10.00").andExpect(status().isOk());
        update(seller, id, "12.00").andExpect(status().isOk());   // back up to what was paid for
        update(seller, id, "12.01").andExpect(status().isConflict());
    }

    @Test
    void aListingThatPostedForFree_cantBeRaisedPastTheThreshold_toDodgeTheFee() throws Exception {
        long id = createdId(create(seller, "5.00"));

        update(seller, id, "5.01").andExpect(status().isConflict());
        update(seller, id, "250").andExpect(status().isConflict());
        update(seller, id, "3.00").andExpect(status().isOk());
        update(seller, id, "5.00").andExpect(status().isOk());
    }

    @Test
    void existingListingsFromBeforePostingFees_areGrandfathered() throws Exception {
        Listing old = listingRepository.save(Listing.builder().title("Old lamp").price(new BigDecimal("10.00"))
                .category(Category.OTHER).owner(seller).build()); // active, no posting basis recorded

        update(seller, old.getId(), "400.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---- stale pending listings ----

    @Test
    void theCleanupDeletesListingsThatWaitedOver24Hours_andNothingElse() throws Exception {
        long stale = createdId(create(seller, "12.00"));
        long fresh = createdId(create(seller, "12.00"));
        long paid = createdId(create(seller, "12.00"));
        completePayment(checkoutSessionId(seller, paid));
        long freeOld = createdId(create(seller, "3.00"));
        for (long id : new long[] {stale, paid, freeOld}) {
            jdbc.update("update listings set created_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(30))), id);
        }
        jdbc.update("update listings set created_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(23))), fresh);

        assertThat(listingService.deleteStalePending(Instant.now())).isEqualTo(1);

        assertThat(listingRepository.findById(stale)).isEmpty();
        assertThat(listingRepository.findById(fresh)).isPresent();   // 23 hours: not yet
        assertThat(listingRepository.findById(paid)).isPresent();    // published, however old
        assertThat(listingRepository.findById(freeOld)).isPresent();
        assertThat(listingService.deleteStalePending(Instant.now())).isZero();
    }

    @Test
    void aFeePaidAfterTheCleanup_isRefunded_andTheDeletedListingStaysGone() throws Exception {
        long id = createdId(create(seller, "12.00"));
        String session = checkoutSessionId(seller, id);
        jdbc.update("update listings set created_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(25))), id);
        listingService.deleteStalePending(Instant.now());

        completePayment(session);

        assertThat(listingRepository.findById(id)).isEmpty();
        assertThat(paymentRepository.findByStripeSessionId(session).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(checkoutGateway).refund(eq("pi_" + session), anyString());
    }

    // ---- helpers ----

    private ResultActions create(User user, String price) throws Exception {
        return mockMvc.perform(as(post("/api/listings"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Lamp\",\"price\":" + price + ",\"category\":\"OTHER\"}"));
    }

    private long createdId(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private ResultActions update(User user, long id, String price) throws Exception {
        return mockMvc.perform(as(put("/api/listings/{id}", id), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Lamp\",\"price\":" + price + ",\"category\":\"OTHER\"}"));
    }

    private ResultActions fetch(User viewer, long id) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/listings/{id}", id);
        return mockMvc.perform(viewer == null ? request : as(request, viewer));
    }

    private ResultActions quote(String price) throws Exception {
        return mockMvc.perform(get("/api/listings/posting-fee").param("price", price)).andExpect(status().isOk());
    }

    private ResultActions checkout(User user, long id) throws Exception {
        return checkoutPurpose(user, id, "POSTING_FEE");
    }

    private ResultActions checkoutPurpose(User user, long id, String purpose) throws Exception {
        return mockMvc.perform(as(post("/api/payments/checkout-session"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":" + id + ",\"purpose\":\"" + purpose + "\"}"));
    }

    private String checkoutSessionId(User user, long id) throws Exception {
        ResultActions result = checkout(user, id).andExpect(status().isCreated());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("sessionId").asText();
    }

    private void completePayment(String sessionId) throws Exception {
        String payload = """
                {"id":"evt_%s_%d","object":"event","api_version":"%s","created":%d,"livemode":false,
                 "pending_webhooks":1,"request":{"id":null,"idempotency_key":null},"type":"checkout.session.completed",
                 "data":{"object":{"id":"%s","object":"checkout.session","mode":"payment",
                                   "payment_status":"paid","status":"complete","payment_intent":"pi_%s"}}}"""
                .formatted(sessionId, System.nanoTime(), Stripe.API_VERSION, Instant.now().getEpochSecond(), sessionId, sessionId);
        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", Webhook.Signature.generateSignatureHeader(payload, SECRET))
                        .content(payload))
                .andExpect(status().isOk());
    }

    private Listing reload(long id) {
        return listingRepository.findById(id).orElseThrow();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwtService.generateAccessToken(UserPrincipal.from(user)));
    }

    private User saveUser(String email, String name, Role role) {
        return userRepository.save(User.builder().fullName(name).email(email).password("x").role(role).enabled(true).build());
    }
}
