package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import com.campus.marketplace.entity.PayoutStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.PaymentProviderException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.CheckoutGateway;
import com.campus.marketplace.service.MailService;
import com.campus.marketplace.service.ReservationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.net.Webhook;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole trade lifecycle through the real endpoints and webhook: reserve, buy, seller cancels, buyer
 * releases, expiry, orders and the admin ledger. Stripe and SMTP are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TradeFlowTest {

    private static final String SECRET = "whsec_test_secret"; // matches src/test/resources/application.yml
    private static final String SELLER_PHONE = "+8801711111111";
    private static final String BUYER_PHONE = "+8801722222222";
    private static final String OTHER_PHONE = "+8801733333333";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private com.campus.marketplace.repository.NotificationRepository notificationRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ReservationService reservationService;
    @Autowired private com.campus.marketplace.service.ReservationExpiryJob expiryJob;
    @MockitoBean private CheckoutGateway checkoutGateway;
    @MockitoBean private MailService mailService;

    private User seller;
    private User buyer;
    private User other;
    private User admin;
    private Listing lamp; // $12.00: deposit $0.50, balance $11.50
    private final AtomicInteger sessions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        cleanUp();
        sessions.set(0);
        seller = saveUser("seller@seu.edu.bd", "Sally Seller", SELLER_PHONE, Role.STUDENT);
        buyer = saveUser("buyer@seu.edu.bd", "Bob Buyer", BUYER_PHONE, Role.STUDENT);
        other = saveUser("other@seu.edu.bd", "Olga Other", OTHER_PHONE, Role.STUDENT);
        admin = saveUser("admin@seu.edu.bd", "Ada Admin", null, Role.ADMIN);
        lamp = saveListing("Desk lamp", "12.00", seller);
        when(checkoutGateway.createCheckoutSession(any())).thenAnswer(inv ->
                new CheckoutGateway.CheckoutSession("cs_" + sessions.incrementAndGet(), "https://checkout.stripe.com/c/pay/x"));
    }

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
        paymentRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- reserve ----

    @Test
    void reservation_setsAnExpiry48HoursOut_keepsThePaymentIntent_andEmailsBothSides() throws Exception {
        String session = checkoutSession(buyer, lamp, "RESERVE_ITEM");
        completePayment(session, "pi_deposit");

        Listing reserved = reload(lamp);
        assertThat(reserved.getReservedBy().getId()).isEqualTo(buyer.getId());
        assertThat(reserved.getReservationExpiresAt())
                .isCloseTo(Instant.now().plus(Duration.ofHours(48)), within(1, ChronoUnit.MINUTES));
        Payment deposit = payment(session);
        assertThat(deposit.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(deposit.getAmount()).isEqualTo(50L);
        assertThat(deposit.getStripePaymentIntentId()).isEqualTo("pi_deposit");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService, times(2)).sendNotification(to.capture(), anyString(), body.capture());
        int sellerMail = to.getAllValues().indexOf("seller@seu.edu.bd");
        int buyerMail = to.getAllValues().indexOf("buyer@seu.edu.bd");
        assertThat(sellerMail).isGreaterThanOrEqualTo(0);
        assertThat(buyerMail).isGreaterThanOrEqualTo(0);
        // The seller learns who reserved and how to reach them.
        assertThat(body.getAllValues().get(sellerMail)).contains("Bob Buyer").contains(BUYER_PHONE).contains("$0.50");
        // The buyer gets the seller's phone, the expiry and what Buy will cost.
        assertThat(body.getAllValues().get(buyerMail)).contains(SELLER_PHONE).contains("$11.50")
                .contains("/listings/" + lamp.getId()).contains("not refunded");
    }

    @Test
    void aFailingEmailNeverUndoesTheReservation() throws Exception {
        doThrow(new com.campus.marketplace.exception.EmailDeliveryException("smtp down", null))
                .when(mailService).sendNotification(anyString(), anyString(), anyString());

        completePayment(checkoutSession(buyer, lamp, "RESERVE_ITEM"), "pi_deposit");

        assertThat(reload(lamp).isReserved()).isTrue();
    }

    @Test
    void duplicateReserveWebhook_sendsEmailsOnce() throws Exception {
        String session = checkoutSession(buyer, lamp, "RESERVE_ITEM");
        completePayment(session, "pi_deposit");
        completePayment(session, "pi_deposit");

        verify(mailService, times(2)).sendNotification(anyString(), anyString(), anyString());
    }

    // ---- in-app notifications ----

    @Test
    void reservePurchaseAndCashHandover_notifyTheRightPeople_onceEach() throws Exception {
        String session = checkoutSession(buyer, lamp, "RESERVE_ITEM");
        completePayment(session, "pi_deposit");
        completePayment(session, "pi_deposit"); // duplicate webhook delivery
        assertThat(notificationRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getUser().getId()).isEqualTo(seller.getId());
            assertThat(n.getType()).isEqualTo(com.campus.marketplace.entity.NotificationType.RESERVED);
            assertThat(n.getBody()).contains("Bob Buyer").contains("Desk lamp");
            assertThat(n.getLink()).isEqualTo("/listings/" + lamp.getId());
        });

        completePayment(checkoutSession(buyer, lamp, "PURCHASE"), "pi_balance");
        assertThat(notificationRepository.findAll()).hasSize(2)
                .anySatisfy(n -> {
                    assertThat(n.getType()).isEqualTo(com.campus.marketplace.entity.NotificationType.PURCHASED);
                    assertThat(n.getUser().getId()).isEqualTo(seller.getId());
                    assertThat(n.getLink()).isEqualTo("/sales");
                });
    }

    @Test
    void aCashHandover_notifiesTheBuyer() throws Exception {
        reserve(lamp, buyer);
        notificationRepository.deleteAll();
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), seller)).andExpect(status().isOk());
        assertThat(notificationRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getUser().getId()).isEqualTo(buyer.getId());
            assertThat(n.getType()).isEqualTo(com.campus.marketplace.entity.NotificationType.CASH_HANDOVER_CONFIRMED);
            assertThat(n.getLink()).isEqualTo("/purchases");
        });
    }

    // ---- buy ----

    @Test
    void buy_chargesThePriceMinusTheDeposit_computedOnTheServer() throws Exception {
        reserve(lamp, buyer);

        checkoutRaw(buyer, lamp, "PURCHASE", ", \"amount\": 1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1150))
                .andExpect(jsonPath("$.purpose").value("PURCHASE"));

        ArgumentCaptor<CheckoutGateway.CheckoutRequest> sent = ArgumentCaptor.forClass(CheckoutGateway.CheckoutRequest.class);
        verify(checkoutGateway, times(2)).createCheckoutSession(sent.capture()); // the deposit, then the balance
        assertThat(sent.getAllValues().get(1).amount()).isEqualTo(1150L);
        assertThat(sent.getAllValues().get(1).metadata()).containsEntry("purpose", "PURCHASE");
        Payment purchase = paymentRepository.findAll().stream()
                .filter(p -> p.getPurpose() == PaymentPurpose.PURCHASE).findFirst().orElseThrow();
        assertThat(purchase.getBasisPriceCents()).isEqualTo(1200L);
        assertThat(purchase.getRateBasisPoints()).isNull();
    }

    @Test
    void buy_isOnlyForTheReserver() throws Exception {
        reserve(lamp, buyer);

        checkout(other, lamp, "PURCHASE").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only the student who reserved this item can buy it"));
        checkout(seller, lamp, "PURCHASE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't buy your own listing"));
        mockMvc.perform(post("/api/payments/checkout-session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":" + lamp.getId() + ",\"purpose\":\"PURCHASE\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(paymentRepository.findAll()).noneMatch(p -> p.getPurpose() == PaymentPurpose.PURCHASE);
    }

    @Test
    void buy_requiresAReservation() throws Exception {
        checkout(buyer, lamp, "PURCHASE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Reserve this item before you buy it"));
    }

    @Test
    void buy_isRefusedOnceTheReservationHasExpired() throws Exception {
        reserve(lamp, buyer);
        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l);

        checkout(buyer, lamp, "PURCHASE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your reservation has expired"));
    }

    @Test
    void buy_isRefusedForASoldListing() throws Exception {
        reserve(lamp, buyer);
        buy(lamp, buyer);
        checkout(buyer, lamp, "PURCHASE").andExpect(status().isConflict());
    }

    @Test
    void purchaseWebhook_marksSold_recordsTheOrder_clearsTheReservation_andEmailsBothSides() throws Exception {
        reserve(lamp, buyer);
        org.mockito.Mockito.clearInvocations(mailService);

        String session = checkoutSession(buyer, lamp, "PURCHASE");
        completePayment(session, "pi_balance");

        Listing sold = reload(lamp);
        assertThat(sold.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(sold.getBuyer().getId()).isEqualTo(buyer.getId());
        assertThat(sold.getSoldAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(sold.isReserved()).isFalse();
        assertThat(sold.getReservationExpiresAt()).isNull();

        assertThat(orderRepository.findAll()).singleElement().satisfies(o -> {
            assertThat(o.getItemPriceCents()).isEqualTo(1200L);
            assertThat(o.getDepositCents()).isEqualTo(50L);
            assertThat(o.getBalanceCents()).isEqualTo(1150L);
            assertThat(o.getCurrency()).isEqualTo("usd");
            assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(o.getPayoutStatus()).isEqualTo(PayoutStatus.PENDING);
            assertThat(o.getListingTitle()).isEqualTo("Desk lamp");
        });
        assertThat(payment(session).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService, times(2)).sendNotification(to.capture(), anyString(), body.capture());
        assertThat(to.getAllValues()).containsExactlyInAnyOrder("seller@seu.edu.bd", "buyer@seu.edu.bd");
        assertThat(String.join("\n", body.getAllValues())).contains(BUYER_PHONE).contains(SELLER_PHONE).contains("$12.00");
    }

    @Test
    void purchase_copiesTheListingsMeetupSpotOntoTheOrder() throws Exception {
        Listing l = reload(lamp);
        l.setMeetupSpot(com.campus.marketplace.entity.MeetupSpot.CAFETERIA);
        listingRepository.save(l);
        reserve(lamp, buyer);
        buy(lamp, buyer);

        assertThat(orderRepository.findAll()).singleElement()
                .satisfies(o -> assertThat(o.getMeetupSpot()).isEqualTo(com.campus.marketplace.entity.MeetupSpot.CAFETERIA));
        mockMvc.perform(as(get("/api/orders/purchases"), buyer)).andExpect(jsonPath("$.content[0].meetupSpot").value("CAFETERIA"));
    }

    @Test
    void purchaseWebhook_isIdempotent() throws Exception {
        reserve(lamp, buyer);
        String session = checkoutSession(buyer, lamp, "PURCHASE");
        completePayment(session, "pi_balance");
        Instant soldAt = reload(lamp).getSoldAt();

        completePayment(session, "pi_balance");
        completePayment(session, "pi_balance");

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(reload(lamp).getSoldAt()).isEqualTo(soldAt);
        verify(checkoutGateway, never()).refund(anyString(), anyString());
    }

    @Test
    void doublePurchase_secondBalanceIsRefundedAutomatically() throws Exception {
        reserve(lamp, buyer);
        String first = checkoutSession(buyer, lamp, "PURCHASE");
        String second = checkoutSession(buyer, lamp, "PURCHASE"); // double click

        completePayment(first, "pi_first");
        completePayment(second, "pi_second");

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(payment(first).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment(second).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(checkoutGateway).refund(eq("pi_second"), eq("refund-payment-" + payment(second).getId()));
    }

    @Test
    void purchase_whenTheReservationLapsedWhilePaying_stillCompletesTheSale() throws Exception {
        reserve(lamp, buyer);
        String session = checkoutSession(buyer, lamp, "PURCHASE");
        // The sweep freed the item between the buyer opening checkout and paying.
        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(5));
        listingRepository.save(l);
        reservationService.releaseExpired();
        assertThat(reload(lamp).isReserved()).isFalse();

        completePayment(session, "pi_balance");

        assertThat(reload(lamp).getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(orderRepository.count()).isEqualTo(1);
        verify(checkoutGateway, never()).refund(anyString(), anyString());
    }

    @Test
    void purchase_whenSomeoneElseNowHoldsTheItem_refundsTheBalance() throws Exception {
        reserve(lamp, buyer);
        String session = checkoutSession(buyer, lamp, "PURCHASE");
        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(5));
        listingRepository.save(l);
        reservationService.releaseExpired();
        reserve(lamp, other); // a second student gets it

        completePayment(session, "pi_balance");

        assertThat(reload(lamp).getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(reload(lamp).getReservedBy().getId()).isEqualTo(other.getId());
        assertThat(orderRepository.count()).isZero();
        assertThat(payment(session).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(checkoutGateway).refund(eq("pi_balance"), anyString());
    }

    // ---- seller cancels ----

    @Test
    void sellerCancels_refundsTheDeposit_andFreesTheItem() throws Exception {
        String session = reserve(lamp, buyer);

        mockMvc.perform(as(post("/api/listings/{id}/reservation/cancel", lamp.getId()), seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(false))
                .andExpect(jsonPath("$.counterparty").doesNotExist());

        verify(checkoutGateway).refund(eq("pi_deposit"), eq("refund-payment-" + payment(session).getId()));
        assertThat(payment(session).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        Listing freed = reload(lamp);
        assertThat(freed.isReserved()).isFalse();
        assertThat(freed.getReservationExpiresAt()).isNull();
        // Available again: another student can reserve it.
        checkout(other, lamp, "RESERVE_ITEM").andExpect(status().isCreated());
    }

    @Test
    void sellerCancels_forADepositPaidBeforePaymentIntentsWereStored_looksItUp() throws Exception {
        String session = reserve(lamp, buyer);
        Payment deposit = payment(session);
        deposit.setStripePaymentIntentId(null);
        paymentRepository.save(deposit);
        when(checkoutGateway.paymentIntentIdOf(session)).thenReturn("pi_from_stripe");

        mockMvc.perform(as(post("/api/listings/{id}/reservation/cancel", lamp.getId()), seller)).andExpect(status().isOk());

        verify(checkoutGateway).refund(eq("pi_from_stripe"), anyString());
        assertThat(payment(session).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void sellerCancels_whenStripeFails_changesNothing() throws Exception {
        String session = reserve(lamp, buyer);
        doThrow(new PaymentProviderException("Refund failed. Please try again.", HttpStatus.BAD_GATEWAY, null))
                .when(checkoutGateway).refund(anyString(), anyString());

        mockMvc.perform(as(post("/api/listings/{id}/reservation/cancel", lamp.getId()), seller))
                .andExpect(status().isBadGateway());

        assertThat(reload(lamp).isReserved()).isTrue();
        assertThat(payment(session).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void onlyTheSellerCanCancel() throws Exception {
        reserve(lamp, buyer);

        for (User not : new User[] {buyer, other, admin}) {
            mockMvc.perform(as(post("/api/listings/{id}/reservation/cancel", lamp.getId()), not))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/listings/{id}/reservation/cancel", lamp.getId())).andExpect(status().isUnauthorized());
        assertThat(reload(lamp).isReserved()).isTrue();
        verify(checkoutGateway, never()).refund(anyString(), anyString());
    }

    @Test
    void cancelOnAnUnreservedListing_returns409_andUnknownReturns404() throws Exception {
        mockMvc.perform(as(post("/api/listings/{id}/reservation/cancel", lamp.getId()), seller))
                .andExpect(status().isConflict());
        mockMvc.perform(as(post("/api/listings/{id}/reservation/cancel", 999_999), seller))
                .andExpect(status().isNotFound());
    }

    // ---- buyer releases ----

    @Test
    void buyerReleases_freesTheItem_butKeepsTheDeposit() throws Exception {
        String session = reserve(lamp, buyer);

        mockMvc.perform(as(post("/api/listings/{id}/reservation/release", lamp.getId()), buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(false))
                .andExpect(jsonPath("$.reservedByMe").value(false));

        assertThat(reload(lamp).isReserved()).isFalse();
        assertThat(payment(session).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(checkoutGateway, never()).refund(anyString(), anyString());
    }

    @Test
    void onlyTheReserverCanRelease() throws Exception {
        reserve(lamp, buyer);
        for (User not : new User[] {seller, other}) {
            mockMvc.perform(as(post("/api/listings/{id}/reservation/release", lamp.getId()), not))
                    .andExpect(status().isForbidden());
        }
        assertThat(reload(lamp).isReserved()).isTrue();
        mockMvc.perform(as(post("/api/listings/{id}/reservation/release", lamp.getId()), buyer)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/listings/{id}/reservation/release", lamp.getId()), buyer))
                .andExpect(status().isConflict());
    }

    // ---- expiry ----

    @Test
    void expiry_releasesOnlyLapsedReservations_withoutRefunding() throws Exception {
        Listing second = saveListing("Chair", "30.00", seller);
        String lapsed = reserve(lamp, buyer);
        reserve(second, other);
        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l);

        assertThat(reservationService.releaseExpired()).isEqualTo(1);

        assertThat(reload(lamp).isReserved()).isFalse();
        assertThat(reload(second).isReserved()).isTrue();
        assertThat(payment(lapsed).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED); // deposit kept (D5)
        verify(checkoutGateway, never()).refund(anyString(), anyString());
        assertThat(reservationService.releaseExpired()).isZero(); // idempotent
    }

    @Test
    void aLapsedReservation_neverBlocksANewReserver_evenBeforeTheSweepHasRun() throws Exception {
        reserve(lamp, buyer);
        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l); // lapsed, but no sweep has run yet

        checkout(other, lamp, "RESERVE_ITEM").andExpect(status().isCreated());

        assertThat(reload(lamp).isReserved()).isFalse(); // freed on the way; the new deposit reserves it on payment
    }

    @Test
    void aLiveReservation_stillBlocksOthers() throws Exception {
        reserve(lamp, buyer);
        checkout(other, lamp, "RESERVE_ITEM").andExpect(status().isConflict());
        assertThat(reload(lamp).getReservedBy().getId()).isEqualTo(buyer.getId());
    }

    @Test
    void theScheduledJob_releasesLapsedReservations_whenCalledTheWayTheSchedulerCallsIt() throws Exception {
        reserve(lamp, buyer);
        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l);

        expiryJob.sweep(); // no surrounding transaction, as in production

        assertThat(reload(lamp).isReserved()).isFalse();
    }

    @Test
    void expiry_givesOlderReservationsWithoutAnEndTimeAFullWindow_insteadOfExpiringThem() throws Exception {
        reserve(lamp, buyer);
        Listing legacy = reload(lamp);
        legacy.setReservationExpiresAt(null); // reserved before expiry existed
        listingRepository.save(legacy);

        assertThat(reservationService.releaseExpired()).isZero();

        Listing after = reload(lamp);
        assertThat(after.isReserved()).isTrue();
        assertThat(after.getReservationExpiresAt())
                .isCloseTo(Instant.now().plus(Duration.ofHours(48)), within(1, ChronoUnit.MINUTES));
    }

    // ---- reserved items can't be sold or deleted behind the buyer's back ----

    @Test
    void markSold_andOwnerDelete_areRefusedWhileReserved() throws Exception {
        reserve(lamp, buyer);

        mockMvc.perform(as(patch("/api/listings/{id}/sold", lamp.getId()), seller))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("This item is reserved")));
        mockMvc.perform(as(delete("/api/listings/{id}", lamp.getId()), seller)).andExpect(status().isConflict());

        assertThat(listingRepository.findById(lamp.getId())).isPresent();
        assertThat(reload(lamp).getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    void adminDeletingAReservedListing_refundsTheDeposit() throws Exception {
        String session = reserve(lamp, buyer);

        mockMvc.perform(as(delete("/api/admin/listings/{id}", lamp.getId()), admin)).andExpect(status().isNoContent());

        assertThat(listingRepository.findById(lamp.getId())).isEmpty();
        verify(checkoutGateway).refund(eq("pi_deposit"), anyString());
        assertThat(payment(session).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void unreservedListing_canBeMarkedSoldAndDeletedAsBefore() throws Exception {
        mockMvc.perform(as(patch("/api/listings/{id}/sold", lamp.getId()), seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SOLD"));
        assertThat(reload(lamp).getSoldAt()).isNotNull();
        mockMvc.perform(as(delete("/api/listings/{id}", lamp.getId()), seller)).andExpect(status().isNoContent());
    }

    // ---- who sees what on a reserved listing ----

    @Test
    void listingDetail_showsTheCounterpartyOnlyToTheTwoParties() throws Exception {
        reserve(lamp, buyer);

        // The seller sees the reserver and the deadline.
        mockMvc.perform(as(get("/api/listings/{id}", lamp.getId()), seller))
                .andExpect(jsonPath("$.reserved").value(true))
                .andExpect(jsonPath("$.reservedByMe").value(false))
                .andExpect(jsonPath("$.reservationExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.counterparty.fullName").value("Bob Buyer"))
                .andExpect(jsonPath("$.counterparty.phone").value(BUYER_PHONE));
        // The reserver sees the seller and the deadline.
        mockMvc.perform(as(get("/api/listings/{id}", lamp.getId()), buyer))
                .andExpect(jsonPath("$.reservedByMe").value(true))
                .andExpect(jsonPath("$.reservationExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.counterparty.fullName").value("Sally Seller"))
                .andExpect(jsonPath("$.counterparty.phone").value(SELLER_PHONE));
        // Everyone else just sees that it is reserved.
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                as(get("/api/listings/{id}", lamp.getId()), other), get("/api/listings/{id}", lamp.getId())}) {
            String body = mockMvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.reserved").value(true))
                    .andExpect(jsonPath("$.reservedByMe").value(false))
                    .andExpect(jsonPath("$.reservationExpiresAt").doesNotExist())
                    .andExpect(jsonPath("$.counterparty").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(BUYER_PHONE).doesNotContain(SELLER_PHONE);
        }
    }

    @Test
    void searchResultsNeverCarryContactDetails_evenForThePartiesThemselves() throws Exception {
        reserve(lamp, buyer);

        for (User viewer : new User[] {seller, buyer}) {
            String body = mockMvc.perform(as(get("/api/listings"), viewer)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).contains("Desk lamp").doesNotContain(BUYER_PHONE).doesNotContain(SELLER_PHONE)
                    .doesNotContain("Bob Buyer");
        }
    }

    @Test
    void afterTheSale_bothPartiesSeeEachOther_strangersDont() throws Exception {
        reserve(lamp, buyer);
        buy(lamp, buyer);

        mockMvc.perform(as(get("/api/listings/{id}", lamp.getId()), seller))
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andExpect(jsonPath("$.counterparty.phone").value(BUYER_PHONE));
        mockMvc.perform(as(get("/api/listings/{id}", lamp.getId()), buyer))
                .andExpect(jsonPath("$.counterparty.phone").value(SELLER_PHONE));
        mockMvc.perform(as(get("/api/listings/{id}", lamp.getId()), other))
                .andExpect(jsonPath("$.counterparty").doesNotExist());
    }

    // ---- orders ----

    @Test
    void purchasesAndSales_listOnlyTheCallersOwnOrders_withTheOtherSidesPhone() throws Exception {
        reserve(lamp, buyer);
        buy(lamp, buyer);

        mockMvc.perform(as(get("/api/orders/purchases"), buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].listingTitle").value("Desk lamp"))
                .andExpect(jsonPath("$.content[0].itemPriceCents").value(1200))
                .andExpect(jsonPath("$.content[0].depositCents").value(50))
                .andExpect(jsonPath("$.content[0].balanceCents").value(1150))
                .andExpect(jsonPath("$.content[0].status").value("PAID"))
                .andExpect(jsonPath("$.content[0].counterparty.fullName").value("Sally Seller"))
                .andExpect(jsonPath("$.content[0].counterparty.phone").value(SELLER_PHONE));
        mockMvc.perform(as(get("/api/orders/sales"), seller))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].counterparty.fullName").value("Bob Buyer"))
                .andExpect(jsonPath("$.content[0].counterparty.phone").value(BUYER_PHONE));
        // Not mine, not shown: the buyer has no sales, the seller no purchases, a stranger nothing.
        mockMvc.perform(as(get("/api/orders/sales"), buyer)).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(as(get("/api/orders/purchases"), seller)).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(as(get("/api/orders/purchases"), other)).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/orders/purchases")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(get("/api/orders/purchases").param("size", "500"), buyer)).andExpect(status().isBadRequest());
    }

    @Test
    void anOrderOutlivesItsListing() throws Exception {
        reserve(lamp, buyer);
        buy(lamp, buyer);
        listingRepository.deleteById(lamp.getId());

        mockMvc.perform(as(get("/api/orders/purchases"), buyer))
                .andExpect(jsonPath("$.content[0].listingTitle").value("Desk lamp"))
                .andExpect(jsonPath("$.content[0].listingId").doesNotExist());
    }

    @Test
    void adminOrdersLedger_andPayout() throws Exception {
        reserve(lamp, buyer);
        buy(lamp, buyer);
        Order order = orderRepository.findAll().get(0);

        mockMvc.perform(as(get("/api/admin/orders"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].buyer.email").value("buyer@seu.edu.bd"))
                .andExpect(jsonPath("$.content[0].seller.email").value("seller@seu.edu.bd"))
                .andExpect(jsonPath("$.content[0].payoutStatus").value("PENDING"));

        // Not while the order is still waiting for the buyer.
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a completed order can be paid out"));
        mockMvc.perform(as(post("/api/orders/{id}/confirm", order.getId()), buyer)).andExpect(status().isOk());

        for (int i = 0; i < 2; i++) { // idempotent
            mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.payoutStatus").value("PAID_OUT"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.completedAt").isNotEmpty());
        }
        Instant completedAt = orderRepository.findById(order.getId()).orElseThrow().getCompletedAt();
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), admin)).andExpect(status().isOk());
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getCompletedAt()).isEqualTo(completedAt);
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", 999_999), admin)).andExpect(status().isNotFound());
    }

    @Test
    void adminOrderEndpoints_areAdminOnly() throws Exception {
        reserve(lamp, buyer);
        buy(lamp, buyer);
        Order order = orderRepository.findAll().get(0);

        for (User not : new User[] {seller, buyer, other}) {
            mockMvc.perform(as(get("/api/admin/orders"), not)).andExpect(status().isForbidden());
            mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), not)).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/admin/orders")).andExpect(status().isUnauthorized());
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPayoutStatus()).isEqualTo(PayoutStatus.PENDING);
    }

    // ---- deactivated sellers' listings can't be reserved/bought ----

    @Test
    void reserve_andBuy_areRefusedWhenTheSellerIsDeactivated() throws Exception {
        reserve(lamp, buyer);
        seller.setActive(false);
        userRepository.save(seller);

        checkout(buyer, lamp, "PURCHASE").andExpect(status().isNotFound());
    }

    // ---- cash at the meetup ----

    @Test
    void theReserverCanChooseCash_seeingTheExactAmount_andOnlyTheTwoSidesSeeTheChoice() throws Exception {
        reserve(lamp, buyer);

        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashChosen").value(true))
                .andExpect(jsonPath("$.balanceDueCents").value(1150))   // price $12.00 minus the $0.50 deposit
                .andExpect(jsonPath("$.counterparty.phone").value(SELLER_PHONE));
        // Idempotent, and nothing was charged.
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        verify(checkoutGateway, times(1)).createCheckoutSession(any()); // only the deposit's

        mockMvc.perform(as(get("/api/listings/{id}", lamp.getId()), seller))
                .andExpect(jsonPath("$.cashChosen").value(true)).andExpect(jsonPath("$.balanceDueCents").value(1150));
        mockMvc.perform(as(get("/api/listings/{id}", lamp.getId()), other))
                .andExpect(jsonPath("$.cashChosen").value(false)).andExpect(jsonPath("$.balanceDueCents").doesNotExist());
        mockMvc.perform(get("/api/listings/{id}", lamp.getId()))
                .andExpect(jsonPath("$.cashChosen").value(false)).andExpect(jsonPath("$.balanceDueCents").doesNotExist());
    }

    @Test
    void choosingCash_isOnlyForTheReserver_andNeedsALiveReservation() throws Exception {
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isConflict()); // not reserved
        reserve(lamp, buyer);

        for (User not : new User[] {seller, other, admin}) {
            mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), not)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/listings/{id}/cash", lamp.getId())).andExpect(status().isUnauthorized());
        assertThat(reload(lamp).getCashChosenAt()).isNull();

        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This reservation has expired"));
    }

    @Test
    void theSellerConfirmsTheHandover_andTheCashSaleIsRecordedAsACompletedCashOrder() throws Exception {
        reserve(lamp, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        org.mockito.Mockito.clearInvocations(mailService);

        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.itemPriceCents").value(1200))
                .andExpect(jsonPath("$.depositCents").value(50))
                .andExpect(jsonPath("$.balanceCents").value(1150))
                .andExpect(jsonPath("$.counterparty.phone").value(BUYER_PHONE));

        Listing sold = reload(lamp);
        assertThat(sold.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(sold.getBuyer().getId()).isEqualTo(buyer.getId());
        assertThat(sold.isReserved()).isFalse();
        assertThat(sold.getCashChosenAt()).isNull();
        assertThat(orderRepository.findAll()).singleElement().satisfies(o -> {
            assertThat(o.getPaymentMethod()).isEqualTo(com.campus.marketplace.entity.PaymentMethod.CASH);
            assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(o.getPayment()).isNull(); // nothing went through Stripe
        });
        // No money moved through the platform for the balance: only the deposit payment exists.
        assertThat(paymentRepository.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getPurpose()).isEqualTo(PaymentPurpose.RESERVE_ITEM));

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService, times(2)).sendNotification(to.capture(), anyString(), body.capture());
        assertThat(to.getAllValues()).containsExactlyInAnyOrder("seller@seu.edu.bd", "buyer@seu.edu.bd");
        assertThat(String.join("\n", body.getAllValues())).contains("$11.50").contains("in cash");
        // Both sides then see the order, with the other's phone.
        mockMvc.perform(as(get("/api/orders/purchases"), buyer)).andExpect(jsonPath("$.content[0].paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.content[0].counterparty.phone").value(SELLER_PHONE));
    }

    @Test
    void onlyTheListingsSellerCanConfirm_andOnlyAfterTheBuyerChoseCash() throws Exception {
        reserve(lamp, buyer);
        // Cash not chosen yet.
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), seller)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The buyer hasn't chosen to pay cash"));
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());

        for (User not : new User[] {buyer, other, admin}) {
            mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), not)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/listings/{id}/cash/confirm", lamp.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", 999_999), seller)).andExpect(status().isNotFound());
        assertThat(orderRepository.count()).isZero();
        assertThat(reload(lamp).getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    void anotherSellersListing_cantBeConfirmedByAStudentWhoOwnsADifferentOne() throws Exception {
        reserve(lamp, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        Listing othersListing = saveListing("Chair", "30.00", other);
        reserve(othersListing, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", othersListing.getId()), buyer)).andExpect(status().isOk());

        // `other` owns the chair, not the lamp.
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), other)).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", othersListing.getId()), seller)).andExpect(status().isForbidden());
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void aSecondConfirmationIsRefused_andCreatesNoSecondOrder() throws Exception {
        reserve(lamp, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), seller)).andExpect(status().isOk());
        Instant soldAt = reload(lamp).getSoldAt();

        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), seller)).andExpect(status().isConflict());

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(reload(lamp).getSoldAt()).isEqualTo(soldAt);
    }

    @Test
    void anExpiredReservationCantBeConfirmed_evenBeforeTheSweepHasFreedIt() throws Exception {
        reserve(lamp, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l);

        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), seller)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This reservation has expired"));

        assertThat(orderRepository.count()).isZero();
        assertThat(reload(lamp).getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    void normalExpiryAppliesToACashChoice_andClearsIt() throws Exception {
        reserve(lamp, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        Listing l = reload(lamp);
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l);

        reservationService.releaseExpired();

        Listing freed = reload(lamp);
        assertThat(freed.isReserved()).isFalse();
        assertThat(freed.getCashChosenAt()).isNull();
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", lamp.getId()), seller)).andExpect(status().isConflict());
    }

    @Test
    void cancellingOrReleasing_clearsTheCashChoice() throws Exception {
        reserve(lamp, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/listings/{id}/reservation/release", lamp.getId()), buyer)).andExpect(status().isOk());
        assertThat(reload(lamp).getCashChosenAt()).isNull();

        reserve(lamp, other);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), other)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/listings/{id}/reservation/cancel", lamp.getId()), seller)).andExpect(status().isOk());
        assertThat(reload(lamp).getCashChosenAt()).isNull();
    }

    @Test
    void payingOnlineInsteadAfterChoosingCash_stillWorks() throws Exception {
        reserve(lamp, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());

        buy(lamp, buyer);

        assertThat(reload(lamp).getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(reload(lamp).getCashChosenAt()).isNull();
        assertThat(orderRepository.findAll()).singleElement()
                .satisfies(o -> assertThat(o.getPaymentMethod()).isEqualTo(com.campus.marketplace.entity.PaymentMethod.ONLINE));
    }

    @Test
    void owedToSeller_isTheDepositOnlyForACashOrder_andTheWholePriceForAnOnlineOne() throws Exception {
        // Online sale: the platform collected deposit + balance.
        reserve(lamp, buyer);
        buy(lamp, buyer);
        // Cash sale: the platform only holds the deposit.
        Listing chair = saveListing("Chair", "30.00", seller);
        reserve(chair, other);
        mockMvc.perform(as(post("/api/listings/{id}/cash", chair.getId()), other)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", chair.getId()), seller)).andExpect(status().isOk());

        String body = mockMvc.perform(as(get("/api/admin/orders"), admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var content = objectMapper.readTree(body).get("content");
        for (var order : content) {
            if ("CASH".equals(order.get("paymentMethod").asText())) {
                assertThat(order.get("itemPriceCents").asLong()).isEqualTo(3000);
                assertThat(order.get("depositCents").asLong()).isEqualTo(150);
                assertThat(order.get("owedToSellerCents").asLong()).as("cash order: the deposit only").isEqualTo(150);
            } else {
                assertThat(order.get("owedToSellerCents").asLong()).as("online order: the whole price").isEqualTo(1200);
            }
        }
        assertThat(content).hasSize(2);
        // A cash order is COMPLETED at once, so it can be paid out (the deposit).
        Order cash = orderRepository.findAll().stream()
                .filter(o -> o.getPaymentMethod() == com.campus.marketplace.entity.PaymentMethod.CASH).findFirst().orElseThrow();
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", cash.getId()), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.owedToSellerCents").value(150));
    }

    // ---- helpers ----

    /** Reserves {@code listing} for {@code user}: checkout, then Stripe's webhook. Returns the session id. */
    private String reserve(Listing listing, User user) throws Exception {
        String session = checkoutSession(user, listing, "RESERVE_ITEM");
        completePayment(session, "pi_deposit");
        return session;
    }

    private void buy(Listing listing, User user) throws Exception {
        completePayment(checkoutSession(user, listing, "PURCHASE"), "pi_balance");
    }

    private String checkoutSession(User user, Listing listing, String purpose) throws Exception {
        ResultActions result = checkout(user, listing, purpose).andExpect(status().isCreated());
        JsonNode json = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        return json.get("sessionId").asText();
    }

    private ResultActions checkout(User user, Listing listing, String purpose) throws Exception {
        return checkoutRaw(user, listing, purpose, "");
    }

    private ResultActions checkoutRaw(User user, Listing listing, String purpose, String extraFields) throws Exception {
        return mockMvc.perform(as(post("/api/payments/checkout-session"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":" + listing.getId() + ",\"purpose\":\"" + purpose + "\"" + extraFields + "}"));
    }

    private void completePayment(String sessionId, String paymentIntentId) throws Exception {
        String payload = """
                {"id":"evt_%s","object":"event","api_version":"%s","created":%d,"livemode":false,
                 "pending_webhooks":1,"request":{"id":null,"idempotency_key":null},"type":"checkout.session.completed",
                 "data":{"object":{"id":"%s","object":"checkout.session","mode":"payment",
                                   "payment_status":"paid","status":"complete","payment_intent":"%s"}}}"""
                .formatted(sessionId, Stripe.API_VERSION, Instant.now().getEpochSecond(), sessionId, paymentIntentId);
        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", Webhook.Signature.generateSignatureHeader(payload, SECRET))
                        .content(payload))
                .andExpect(status().isOk());
    }

    private Payment payment(String sessionId) {
        return paymentRepository.findByStripeSessionId(sessionId).orElseThrow();
    }

    private Listing reload(Listing listing) {
        return listingRepository.findById(listing.getId()).orElseThrow();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwtService.generateAccessToken(UserPrincipal.from(user)));
    }

    private User saveUser(String email, String name, String phone, Role role) {
        return userRepository.save(User.builder().fullName(name).email(email).phone(phone).password("x")
                .role(role).enabled(true).build());
    }

    private Listing saveListing(String title, String price, User owner) {
        return listingRepository.save(Listing.builder()
                .title(title).price(new BigDecimal(price)).category(Category.OTHER).owner(owner).build());
    }
}
