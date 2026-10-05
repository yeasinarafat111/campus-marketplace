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
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.stripe.Stripe;
import com.campus.marketplace.exception.PaymentProviderException;
import com.campus.marketplace.service.CheckoutGateway;
import com.campus.marketplace.service.MailService;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sends correctly signed (and deliberately badly signed) Stripe events to the real endpoint, without a JWT. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentWebhookTest {

    private static final String SECRET = "whsec_test_secret"; // matches src/test/resources/application.yml

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    // Refunds and emails go through these; tests never talk to Stripe or an SMTP server.
    @MockitoBean private CheckoutGateway checkoutGateway;
    @MockitoBean private MailService mailService;

    private User seller;
    private User buyer;
    private User secondBuyer;
    private Listing listing;

    @BeforeEach
    void setUp() {
        cleanUp();
        seller = saveUser("seller@seu.edu.bd");
        buyer = saveUser("buyer@seu.edu.bd");
        secondBuyer = saveUser("buyer2@seu.edu.bd");
        listing = listingRepository.save(Listing.builder()
                .title("Calculus Textbook").price(new BigDecimal("450")).category(Category.BOOKS).owner(seller).build());
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- effects ----

    @Test
    void completedFeaturePayment_marksSucceeded_andFeaturesListingFor7Days() throws Exception {
        savePayment("cs_feature", PaymentPurpose.FEATURE_LISTING, seller);

        send(event("evt_1", "checkout.session.completed", "cs_feature", "paid")).andExpect(status().isOk());

        assertThat(payment("cs_feature").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reloadListing().getFeaturedUntil())
                .isCloseTo(Instant.now().plus(Duration.ofDays(7)), within(1, ChronoUnit.MINUTES));
        mockMvc.perform(get("/api/listings/{id}", listing.getId()))
                .andExpect(jsonPath("$.featured").value(true))
                .andExpect(jsonPath("$.reserved").value(false));
    }

    @Test
    void completedReservePayment_marksSucceeded_andReservesForThatBuyer() throws Exception {
        savePayment("cs_reserve", PaymentPurpose.RESERVE_ITEM, buyer);

        send(event("evt_1", "checkout.session.completed", "cs_reserve", "paid")).andExpect(status().isOk());

        assertThat(payment("cs_reserve").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reservedById()).isEqualTo(buyer.getId());
        assertThat(reloadListing().getReservedAt()).isNotNull();
        mockMvc.perform(get("/api/listings/{id}", listing.getId())).andExpect(jsonPath("$.reserved").value(true));
    }

    // ---- idempotency ----

    @Test
    void duplicateDelivery_doesNotReapply() throws Exception {
        savePayment("cs_feature", PaymentPurpose.FEATURE_LISTING, seller);
        String event = event("evt_1", "checkout.session.completed", "cs_feature", "paid");

        send(event).andExpect(status().isOk());
        Instant firstExpiry = reloadListing().getFeaturedUntil();
        Thread.sleep(5);
        send(event).andExpect(status().isOk());

        assertThat(reloadListing().getFeaturedUntil()).isEqualTo(firstExpiry);
        assertThat(payment("cs_feature").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void secondReservePayment_isAutomaticallyRefunded_andKeepsFirstBuyersReservation() throws Exception {
        // Both buyers opened checkout before either paid.
        savePayment("cs_first", PaymentPurpose.RESERVE_ITEM, buyer);
        savePayment("cs_second", PaymentPurpose.RESERVE_ITEM, secondBuyer);

        send(eventWithIntent("evt_1", "checkout.session.completed", "cs_first", "paid", "pi_first")).andExpect(status().isOk());
        send(eventWithIntent("evt_2", "checkout.session.completed", "cs_second", "paid", "pi_second")).andExpect(status().isOk());

        assertThat(payment("cs_first").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment("cs_second").getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reservedById()).isEqualTo(buyer.getId());
        // Only the second deposit went back, and with an idempotency key tied to the payment.
        verify(checkoutGateway).refund(eq("pi_second"), eq("refund-payment-" + payment("cs_second").getId()));
        verify(checkoutGateway, never()).refund(eq("pi_first"), anyString());
    }

    @Test
    void duplicateDeliveryOfAnAutomaticallyRefundedPayment_doesNotRefundTwice() throws Exception {
        savePayment("cs_first", PaymentPurpose.RESERVE_ITEM, buyer);
        savePayment("cs_second", PaymentPurpose.RESERVE_ITEM, secondBuyer);
        send(eventWithIntent("evt_1", "checkout.session.completed", "cs_first", "paid", "pi_first")).andExpect(status().isOk());
        String second = eventWithIntent("evt_2", "checkout.session.completed", "cs_second", "paid", "pi_second");

        send(second).andExpect(status().isOk());
        send(second).andExpect(status().isOk());

        verify(checkoutGateway, times(1)).refund(eq("pi_second"), anyString());
        assertThat(payment("cs_second").getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void refundFailure_returns5xx_andLeavesThePaymentPendingSoStripeRetries() throws Exception {
        savePayment("cs_first", PaymentPurpose.RESERVE_ITEM, buyer);
        savePayment("cs_second", PaymentPurpose.RESERVE_ITEM, secondBuyer);
        send(eventWithIntent("evt_1", "checkout.session.completed", "cs_first", "paid", "pi_first")).andExpect(status().isOk());
        doThrow(new PaymentProviderException("down", org.springframework.http.HttpStatus.BAD_GATEWAY, null))
                .when(checkoutGateway).refund(eq("pi_second"), anyString());

        send(eventWithIntent("evt_2", "checkout.session.completed", "cs_second", "paid", "pi_second"))
                .andExpect(status().is5xxServerError());
        assertThat(payment("cs_second").getStatus()).isEqualTo(PaymentStatus.PENDING);

        // Stripe redelivers once the refund works.
        doNothing().when(checkoutGateway).refund(eq("pi_second"), anyString());
        send(eventWithIntent("evt_2", "checkout.session.completed", "cs_second", "paid", "pi_second")).andExpect(status().isOk());
        assertThat(payment("cs_second").getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void secondFeaturePayment_isRecordedAsSucceeded_butDoesNotExtend() throws Exception {
        savePayment("cs_first", PaymentPurpose.FEATURE_LISTING, seller);
        savePayment("cs_second", PaymentPurpose.FEATURE_LISTING, seller);

        send(event("evt_1", "checkout.session.completed", "cs_first", "paid")).andExpect(status().isOk());
        Instant expiry = reloadListing().getFeaturedUntil();
        send(event("evt_2", "checkout.session.completed", "cs_second", "paid")).andExpect(status().isOk());

        assertThat(payment("cs_second").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reloadListing().getFeaturedUntil()).isEqualTo(expiry);
    }

    @Test
    void featureAfterPreviousFeatureExpired_isAppliedAgain() throws Exception {
        jdbcTemplate.update("update listings set featured_until = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(1))), listing.getId());
        savePayment("cs_feature", PaymentPurpose.FEATURE_LISTING, seller);

        send(event("evt_1", "checkout.session.completed", "cs_feature", "paid")).andExpect(status().isOk());

        assertThat(reloadListing().isFeatured(Instant.now())).isTrue();
    }

    // ---- other event types and states ----

    @Test
    void completedButUnpaid_staysPending_untilAsyncPaymentSucceeds() throws Exception {
        savePayment("cs_async", PaymentPurpose.RESERVE_ITEM, buyer);

        send(event("evt_1", "checkout.session.completed", "cs_async", "unpaid")).andExpect(status().isOk());
        assertThat(payment("cs_async").getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reservedById()).isNull();

        send(event("evt_2", "checkout.session.async_payment_succeeded", "cs_async", "paid")).andExpect(status().isOk());
        assertThat(payment("cs_async").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reservedById()).isEqualTo(buyer.getId());
    }

    @Test
    void expiredOrFailedSession_marksFailed_butNeverDowngradesSucceeded() throws Exception {
        savePayment("cs_expired", PaymentPurpose.RESERVE_ITEM, buyer);
        savePayment("cs_paid", PaymentPurpose.FEATURE_LISTING, seller);
        send(event("evt_0", "checkout.session.completed", "cs_paid", "paid")).andExpect(status().isOk());

        send(event("evt_1", "checkout.session.expired", "cs_expired", "unpaid")).andExpect(status().isOk());
        send(event("evt_2", "checkout.session.async_payment_failed", "cs_paid", "unpaid")).andExpect(status().isOk());

        assertThat(payment("cs_expired").getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment("cs_paid").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void unknownSession_andUnrelatedEvents_areAcknowledgedAndIgnored() throws Exception {
        send(event("evt_1", "checkout.session.completed", "cs_not_ours", "paid")).andExpect(status().isOk());
        send(event("evt_2", "customer.created", "cus_123", "paid")).andExpect(status().isOk());
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void depositForADeletedListing_isRefunded() throws Exception {
        savePayment("cs_reserve", PaymentPurpose.RESERVE_ITEM, buyer);
        listingRepository.deleteById(listing.getId());

        send(eventWithIntent("evt_1", "checkout.session.completed", "cs_reserve", "paid", "pi_reserve")).andExpect(status().isOk());

        assertThat(payment("cs_reserve").getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(checkoutGateway).refund(eq("pi_reserve"), anyString());
    }

    @Test
    void featurePaymentForADeletedListing_isRecordedWithoutRefund() throws Exception {
        savePayment("cs_feature", PaymentPurpose.FEATURE_LISTING, seller);
        listingRepository.deleteById(listing.getId());

        send(event("evt_1", "checkout.session.completed", "cs_feature", "paid")).andExpect(status().isOk());

        assertThat(payment("cs_feature").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(checkoutGateway, never()).refund(anyString(), anyString());
    }

    @Test
    void depositForAnAlreadySoldListing_isRefunded() throws Exception {
        savePayment("cs_reserve", PaymentPurpose.RESERVE_ITEM, buyer);
        Listing sold = reloadListing();
        sold.setStatus(ListingStatus.SOLD);
        listingRepository.save(sold);

        send(eventWithIntent("evt_1", "checkout.session.completed", "cs_reserve", "paid", "pi_reserve")).andExpect(status().isOk());

        assertThat(payment("cs_reserve").getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reservedById()).isNull();
    }

    @Test
    void refundOfAPaymentWithoutAStoredPaymentIntent_looksItUpFromTheSession() throws Exception {
        savePayment("cs_first", PaymentPurpose.RESERVE_ITEM, buyer);
        savePayment("cs_second", PaymentPurpose.RESERVE_ITEM, secondBuyer);
        send(event("evt_1", "checkout.session.completed", "cs_first", "paid")).andExpect(status().isOk());
        when(checkoutGateway.paymentIntentIdOf("cs_second")).thenReturn("pi_looked_up");

        // The event carries no payment_intent, as for payments made before refunds existed.
        send(event("evt_2", "checkout.session.completed", "cs_second", "paid")).andExpect(status().isOk());

        verify(checkoutGateway).refund(eq("pi_looked_up"), anyString());
        assertThat(payment("cs_second").getStripePaymentIntentId()).isEqualTo("pi_looked_up");
    }

    @Test
    void eventFromDifferentApiVersion_isStillProcessed() throws Exception {
        savePayment("cs_reserve", PaymentPurpose.RESERVE_ITEM, buyer);

        send(event("evt_1", "checkout.session.completed", "cs_reserve", "paid", "2020-08-27")).andExpect(status().isOk());

        assertThat(payment("cs_reserve").getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ---- signature verification ----

    @Test
    void wrongSecret_isRejected_andChangesNothing() throws Exception {
        savePayment("cs_reserve", PaymentPurpose.RESERVE_ITEM, buyer);
        String payload = event("evt_1", "checkout.session.completed", "cs_reserve", "paid");

        postWebhook(payload, Webhook.Signature.generateSignatureHeader(payload, "whsec_attacker"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid Stripe signature"));
        assertUnchanged("cs_reserve");
    }

    @Test
    void tamperedPayload_isRejected() throws Exception {
        savePayment("cs_reserve", PaymentPurpose.RESERVE_ITEM, buyer);
        String signedFor = event("evt_1", "checkout.session.completed", "cs_other", "paid");
        String header = Webhook.Signature.generateSignatureHeader(signedFor, SECRET);

        postWebhook(signedFor.replace("cs_other", "cs_reserve"), header).andExpect(status().isBadRequest());
        assertUnchanged("cs_reserve");
    }

    @Test
    void replayedOldEvent_isRejected() throws Exception {
        savePayment("cs_reserve", PaymentPurpose.RESERVE_ITEM, buyer);
        String payload = event("evt_1", "checkout.session.completed", "cs_reserve", "paid");
        long tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();

        postWebhook(payload, Webhook.Signature.generateSignatureHeader(payload, SECRET, tenMinutesAgo))
                .andExpect(status().isBadRequest());
        assertUnchanged("cs_reserve");
    }

    @Test
    void missingOrGarbageSignature_isRejected() throws Exception {
        savePayment("cs_reserve", PaymentPurpose.RESERVE_ITEM, buyer);
        String payload = event("evt_1", "checkout.session.completed", "cs_reserve", "paid");

        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing Stripe-Signature header"));
        postWebhook(payload, "t=1,v1=deadbeef").andExpect(status().isBadRequest());
        assertUnchanged("cs_reserve");
    }

    @Test
    void endpointIsPublic_butOnlyThisRoute() throws Exception {
        // No JWT needed for the webhook (covered above); other payment routes still require one.
        mockMvc.perform(post("/api/payments/checkout-session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":1,\"purpose\":\"RESERVE_ITEM\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private ResultActions send(String payload) throws Exception {
        return postWebhook(payload, Webhook.Signature.generateSignatureHeader(payload, SECRET));
    }

    private ResultActions postWebhook(String payload, String signature) throws Exception {
        return mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature)
                .content(payload));
    }

    private static String event(String eventId, String type, String sessionId, String paymentStatus) {
        return event(eventId, type, sessionId, paymentStatus, Stripe.API_VERSION, null);
    }

    private static String event(String eventId, String type, String sessionId, String paymentStatus, String apiVersion) {
        return event(eventId, type, sessionId, paymentStatus, apiVersion, null);
    }

    /** A checkout event whose session carries the given PaymentIntent id (null: none, as on old payments). */
    private static String eventWithIntent(String eventId, String type, String sessionId, String paymentStatus, String paymentIntentId) {
        return event(eventId, type, sessionId, paymentStatus, Stripe.API_VERSION, paymentIntentId);
    }

    private static String event(String eventId, String type, String sessionId, String paymentStatus, String apiVersion,
                                String paymentIntentId) {
        String intent = paymentIntentId == null ? "null" : "\"" + paymentIntentId + "\"";
        return """
                {"id":"%s","object":"event","api_version":"%s","created":%d,"livemode":false,
                 "pending_webhooks":1,"request":{"id":null,"idempotency_key":null},"type":"%s",
                 "data":{"object":{"id":"%s","object":"checkout.session","mode":"payment",
                                   "payment_status":"%s","status":"complete","payment_intent":%s}}}"""
                .formatted(eventId, apiVersion, Instant.now().getEpochSecond(), type, sessionId, paymentStatus, intent);
    }

    private void assertUnchanged(String sessionId) {
        assertThat(payment(sessionId).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reservedById()).isNull();
        assertThat(reloadListing().getFeaturedUntil()).isNull();
    }

    private Payment payment(String sessionId) {
        return paymentRepository.findByStripeSessionId(sessionId).orElseThrow();
    }

    private Listing reloadListing() {
        return listingRepository.findById(listing.getId()).orElseThrow();
    }

    private Long reservedById() {
        return jdbcTemplate.queryForObject("select reserved_by_id from listings where id = ?", Long.class, listing.getId());
    }

    private void savePayment(String sessionId, PaymentPurpose purpose, User user) {
        paymentRepository.save(Payment.builder()
                .user(user).listing(listing).amount(purpose == PaymentPurpose.FEATURE_LISTING ? 199L : 500L)
                .currency("usd").purpose(purpose).status(PaymentStatus.PENDING).stripeSessionId(sessionId).build());
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .fullName(email.substring(0, email.indexOf('@'))).email(email).password("x")
                .role(Role.STUDENT).enabled(true).build());
    }
}
