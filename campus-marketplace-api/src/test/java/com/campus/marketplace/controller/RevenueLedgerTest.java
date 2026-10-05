package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.LedgerEntry;
import com.campus.marketplace.entity.LedgerType;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.entity.PaymentMethod;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import com.campus.marketplace.entity.PayoutStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.LedgerEntryRepository;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.CheckoutGateway;
import com.campus.marketplace.service.LedgerBackfill;
import com.campus.marketplace.service.MailService;
import com.campus.marketplace.service.PaymentService;
import com.campus.marketplace.service.ReservationService;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The revenue ledger: what counts as revenue, refunds subtracting, the admin report, CSV and the backfill. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.payments.posting-fee.enabled=true")
class RevenueLedgerTest {

    private static final String SECRET = "whsec_test_secret"; // matches src/test/resources/application.yml

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private LedgerEntryRepository ledgerRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private PaymentService paymentService;
    @Autowired private ReservationService reservationService;
    @Autowired private LedgerBackfill backfill;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private CheckoutGateway checkoutGateway;
    @MockitoBean private MailService mailService;

    private User seller;
    private User buyer;
    private User admin;
    private final AtomicInteger sessions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        cleanUp();
        sessions.set(0);
        seller = saveUser("seller@seu.edu.bd", "Sally Seller", Role.STUDENT);
        buyer = saveUser("buyer@seu.edu.bd", "Bob Buyer", Role.STUDENT);
        admin = saveUser("admin@seu.edu.bd", "Ada Admin", Role.ADMIN);
        when(checkoutGateway.createCheckoutSession(any())).thenAnswer(inv ->
                new CheckoutGateway.CheckoutSession("cs_" + sessions.incrementAndGet(), "https://checkout.stripe.com/x"));
    }

    @AfterEach
    void cleanUp() {
        ledgerRepository.deleteAll();
        orderRepository.deleteAll();
        paymentRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- what is revenue ----

    @Test
    void aFeatureFeeIsBookedOnce_whateverHappensToTheWebhook() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        String session = checkoutSession(seller, lamp, "FEATURE_LISTING");

        completePayment(session);
        completePayment(session);

        assertThat(ledgerRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LedgerType.FEATURE_FEE);
            assertThat(e.getAmountCents()).isEqualTo(50L);
            assertThat(e.getCurrency()).isEqualTo("usd");
            assertThat(e.getListingId()).isEqualTo(lamp.getId());
            assertThat(e.getListingTitle()).isEqualTo("Desk lamp");
            assertThat(e.getUserId()).isEqualTo(seller.getId());
            assertThat(e.getPaymentId()).isNotNull();
        });
    }

    @Test
    void aFeatureFeeForADeletedListingIsStillRevenue() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        String session = checkoutSession(seller, lamp, "FEATURE_LISTING");
        listingRepository.deleteById(lamp.getId());

        completePayment(session);

        assertThat(ledgerRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LedgerType.FEATURE_FEE);
            assertThat(e.getListingId()).isNull();
        });
    }

    @Test
    void aPostingFeeIsBookedWhenItPublishesTheListing_andNotWhenItIsRefunded() throws Exception {
        long id = createdId(createListing(seller, "12.00"));
        String first = postingCheckout(seller, id);
        String second = postingCheckout(seller, id); // a duplicate checkout

        completePayment(first);
        completePayment(second); // refunded automatically: the listing is already live

        assertThat(ledgerRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LedgerType.POSTING_FEE);
            assertThat(e.getAmountCents()).isEqualTo(50L);
        });
        assertThat(paymentRepository.findByStripeSessionId(second).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void aDepositIsNotRevenueWhileItIsHeld_orWhenItIsRefunded_orWhenItBecomesPartOfASale() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        reserve(lamp, buyer);
        assertThat(ledgerRepository.count()).as("held").isZero();

        // The seller cancels: refunded to the buyer.
        mockMvc.perform(as(post("/api/listings/{id}/reservation/cancel", lamp.getId()), seller)).andExpect(status().isOk());
        assertThat(ledgerRepository.count()).as("refunded").isZero();

        // Reserved again and bought online: the deposit is credited toward the price, so it belongs to the seller.
        reserve(lamp, buyer);
        completePayment(checkoutSession(buyer, lamp, "PURCHASE"));
        assertThat(ledgerRepository.count()).as("online sale").isZero();

        // A cash sale likewise.
        Listing chair = activeListing("Chair", "30.00");
        reserve(chair, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", chair.getId()), buyer)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/listings/{id}/cash/confirm", chair.getId()), seller)).andExpect(status().isOk());
        assertThat(ledgerRepository.count()).as("cash sale").isZero();

        // An admin removing a reserved listing refunds the deposit.
        Listing desk = activeListing("Desk", "40.00");
        reserve(desk, buyer);
        mockMvc.perform(as(delete("/api/admin/listings/{id}", desk.getId()), admin)).andExpect(status().isNoContent());
        assertThat(ledgerRepository.count()).as("admin delete").isZero();
    }

    @Test
    void aDepositBecomesRevenueWhenTheBuyerReleasesIt() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        reserve(lamp, buyer);

        mockMvc.perform(as(post("/api/listings/{id}/reservation/release", lamp.getId()), buyer)).andExpect(status().isOk());

        assertThat(ledgerRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LedgerType.FORFEITED_DEPOSIT);
            assertThat(e.getAmountCents()).isEqualTo(50L);
            assertThat(e.getUserId()).isEqualTo(buyer.getId());
            assertThat(e.getListingTitle()).isEqualTo("Desk lamp");
        });
    }

    @Test
    void aDepositBecomesRevenueWhenTheReservationExpires_exactlyOnce() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        reserve(lamp, buyer);
        expire(lamp);

        assertThat(reservationService.releaseExpired()).isEqualTo(1);
        assertThat(reservationService.releaseExpired()).isZero();

        assertThat(ledgerRepository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getType()).isEqualTo(LedgerType.FORFEITED_DEPOSIT));
    }

    @Test
    void aLapsedReservationFreedByANewReserver_forfeitsTheOldDeposit() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        reserve(lamp, buyer);
        expire(lamp);
        User other = saveUser("other@seu.edu.bd", "Olga Other", Role.STUDENT);

        mockMvc.perform(as(post("/api/payments/checkout-session"), other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":" + lamp.getId() + ",\"purpose\":\"RESERVE_ITEM\"}")).andExpect(status().isCreated());

        assertThat(ledgerRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LedgerType.FORFEITED_DEPOSIT);
            assertThat(e.getUserId()).isEqualTo(buyer.getId());
        });
    }

    @Test
    void aCashReservationThatExpiresStillForfeitsItsDeposit() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        reserve(lamp, buyer);
        mockMvc.perform(as(post("/api/listings/{id}/cash", lamp.getId()), buyer)).andExpect(status().isOk());
        expire(lamp);

        reservationService.releaseExpired();

        assertThat(ledgerRepository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getType()).isEqualTo(LedgerType.FORFEITED_DEPOSIT));
    }

    // ---- refunds subtract ----

    @Test
    void aRefundTakesTheRevenueBack_asANegativeEntry_once() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        String session = checkoutSession(seller, lamp, "FEATURE_LISTING");
        completePayment(session);
        Payment payment = paymentRepository.findByStripeSessionId(session).orElseThrow();

        for (int i = 0; i < 2; i++) {
            transactions.executeWithoutResult(tx -> paymentService.refund(paymentRepository.findById(payment.getId()).orElseThrow()));
        }

        List<LedgerEntry> entries = ledgerRepository.findAll();
        assertThat(entries).extracting(LedgerEntry::getAmountCents).containsExactlyInAnyOrder(50L, -50L);
        assertThat(entries).extracting(LedgerEntry::getType).containsOnly(LedgerType.FEATURE_FEE);
        getReport("2000-01-01", "2000-01-02", "day"); // a range with nothing in it is fine
        mockMvc.perform(as(get("/api/admin/revenue"), admin)).andExpect(jsonPath("$.netCents").value(0));
    }

    // ---- the report ----

    @Test
    void theReportTotalsPerSource_andFillsEmptyDays() throws Exception {
        book(LedgerType.POSTING_FEE, 60, "2026-10-01T05:00:00Z");
        book(LedgerType.FEATURE_FEE, 125, "2026-10-01T09:00:00Z");
        book(LedgerType.FORFEITED_DEPOSIT, 50, "2026-10-03T09:00:00Z");
        book(LedgerType.FEATURE_FEE, -125, "2026-10-03T10:00:00Z");   // a refund
        book(LedgerType.POSTING_FEE, 999, "2026-10-04T09:00:00Z");   // outside the range

        getReport("2026-10-01", "2026-10-03", "day")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-01"))
                .andExpect(jsonPath("$.to").value("2026-10-03"))
                .andExpect(jsonPath("$.currency").value("usd"))
                .andExpect(jsonPath("$.totals.POSTING_FEE").value(60))
                .andExpect(jsonPath("$.totals.FEATURE_FEE").value(0))      // 125 - 125
                .andExpect(jsonPath("$.totals.FORFEITED_DEPOSIT").value(50))
                .andExpect(jsonPath("$.netCents").value(110))
                .andExpect(jsonPath("$.series.length()").value(3))
                .andExpect(jsonPath("$.series[0].bucket").value("2026-10-01"))
                .andExpect(jsonPath("$.series[0].netCents").value(185))
                .andExpect(jsonPath("$.series[1].bucket").value("2026-10-02"))
                .andExpect(jsonPath("$.series[1].netCents").value(0))
                .andExpect(jsonPath("$.series[1].byType.POSTING_FEE").value(0))
                .andExpect(jsonPath("$.series[2].netCents").value(-75));
    }

    @Test
    void daysAreCampusDays_notUtcDays() throws Exception {
        // 20:00 UTC on 5 Oct is 02:00 on 6 Oct in Dhaka (UTC+6).
        book(LedgerType.FEATURE_FEE, 100, "2026-10-05T20:00:00Z");

        getReport("2026-10-05", "2026-10-06", "day")
                .andExpect(jsonPath("$.series[0].bucket").value("2026-10-05")).andExpect(jsonPath("$.series[0].netCents").value(0))
                .andExpect(jsonPath("$.series[1].bucket").value("2026-10-06")).andExpect(jsonPath("$.series[1].netCents").value(100));
    }

    @Test
    void theRangeIncludesBothEndDays_andGroupsByWeekOrMonth() throws Exception {
        book(LedgerType.FEATURE_FEE, 10, "2026-09-30T06:00:00Z");   // Wed 30 Sep
        book(LedgerType.FEATURE_FEE, 20, "2026-10-04T06:00:00Z");   // Sun 4 Oct (same week as 30 Sep, Mon 28 Sep)
        book(LedgerType.FEATURE_FEE, 40, "2026-10-05T06:00:00Z");   // Mon 5 Oct (next week)

        getReport("2026-09-30", "2026-10-05", "week")
                .andExpect(jsonPath("$.series.length()").value(2))
                .andExpect(jsonPath("$.series[0].bucket").value("2026-09-28")).andExpect(jsonPath("$.series[0].netCents").value(30))
                .andExpect(jsonPath("$.series[1].bucket").value("2026-10-05")).andExpect(jsonPath("$.series[1].netCents").value(40))
                .andExpect(jsonPath("$.netCents").value(70));
        getReport("2026-09-30", "2026-10-05", "month")
                .andExpect(jsonPath("$.series.length()").value(2))
                .andExpect(jsonPath("$.series[0].bucket").value("2026-09-01")).andExpect(jsonPath("$.series[0].netCents").value(10))
                .andExpect(jsonPath("$.series[1].bucket").value("2026-10-01")).andExpect(jsonPath("$.series[1].netCents").value(60));
    }

    @Test
    void theRangeDefaultsToTheLast30Days_andIsValidated() throws Exception {
        mockMvc.perform(as(get("/api/admin/revenue"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(30));
        getReport("2026-10-05", "2026-10-01", "day").andExpect(status().isBadRequest());
        getReport("2024-01-01", "2026-01-01", "day").andExpect(status().isBadRequest());
        getReport("2026-10-01", "2026-10-02", "hour").andExpect(status().isBadRequest());
        getReport("not-a-date", "2026-10-02", "day").andExpect(status().isBadRequest());
    }

    // ---- not revenue: owed to sellers, and held deposits ----

    @Test
    void owedToSellers_isTheWholePriceOfOnlineSales_butOnlyTheDepositOfCashSales() throws Exception {
        saveOrder("Lamp", PaymentMethod.ONLINE, OrderStatus.COMPLETED, PayoutStatus.PENDING, 1200, 50);
        saveOrder("Desk", PaymentMethod.ONLINE, OrderStatus.PAID, PayoutStatus.PENDING, 3000, 150);   // paid, awaiting the buyer
        saveOrder("Chair", PaymentMethod.CASH, OrderStatus.COMPLETED, PayoutStatus.PENDING, 3000, 150); // only the deposit
        saveOrder("Old book", PaymentMethod.ONLINE, OrderStatus.COMPLETED, PayoutStatus.PAID_OUT, 900, 45); // already paid
        saveOrder("Broken", PaymentMethod.ONLINE, OrderStatus.CANCELLED, PayoutStatus.PENDING, 800, 40);   // cancelled

        mockMvc.perform(as(get("/api/admin/revenue"), admin))
                .andExpect(jsonPath("$.owedToSellersCents").value(1200 + 3000 + 150))
                .andExpect(jsonPath("$.netCents").value(0));   // none of it is revenue
    }

    @Test
    void heldDepositsAreReportedSeparately_andOnlyWhileTheReservationRuns() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        reserve(lamp, buyer);
        mockMvc.perform(as(get("/api/admin/revenue"), admin)).andExpect(jsonPath("$.depositsHeldCents").value(50))
                .andExpect(jsonPath("$.netCents").value(0));

        mockMvc.perform(as(post("/api/listings/{id}/reservation/release", lamp.getId()), buyer)).andExpect(status().isOk());

        mockMvc.perform(as(get("/api/admin/revenue"), admin)).andExpect(jsonPath("$.depositsHeldCents").value(0))
                .andExpect(jsonPath("$.totals.FORFEITED_DEPOSIT").value(50));
    }

    // ---- history and CSV ----

    @Test
    void theHistory_isNewestFirst_filtersByTypeAndDate_andNamesTheListingAndPayer() throws Exception {
        book(LedgerType.POSTING_FEE, 60, "2026-10-01T05:00:00Z");
        book(LedgerType.FEATURE_FEE, 125, "2026-10-02T05:00:00Z");
        book(LedgerType.FEATURE_FEE, 50, "2026-10-03T05:00:00Z");

        mockMvc.perform(as(get("/api/admin/revenue/entries"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].amountCents").value(50))
                .andExpect(jsonPath("$.content[0].type").value("FEATURE_FEE"))
                .andExpect(jsonPath("$.content[0].user.email").value("seller@seu.edu.bd"))
                .andExpect(jsonPath("$.content[0].listing.title").value("Item"))
                .andExpect(jsonPath("$.content[2].type").value("POSTING_FEE"));
        mockMvc.perform(as(get("/api/admin/revenue/entries").param("type", "FEATURE_FEE"), admin))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(as(get("/api/admin/revenue/entries").param("from", "2026-10-02").param("to", "2026-10-02"), admin))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].amountCents").value(125));
        mockMvc.perform(as(get("/api/admin/revenue/entries").param("size", "2"), admin))
                .andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(as(get("/api/admin/revenue/entries").param("size", "500"), admin)).andExpect(status().isBadRequest());
    }

    @Test
    void theCsvExport_hasTheRowsAndNeutralisesFormulas() throws Exception {
        book(LedgerType.FEATURE_FEE, 125, "2026-10-02T05:00:00Z", "=HYPERLINK(\"http://evil\",\"x\")");
        book(LedgerType.FEATURE_FEE, -125, "2026-10-03T05:00:00Z", "Desk, \"big\" one");

        String csv = mockMvc.perform(as(get("/api/admin/revenue/entries/export"), admin)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo("id,date,type,listing_id,listing_title,user_email,payment_id,amount,currency");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains(",FEATURE_FEE,").contains(",1.25,usd")
                .contains("\"'=HYPERLINK(").doesNotContain(",\"=HYPERLINK");   // formula prefixed so a spreadsheet won't run it
        assertThat(lines[2]).contains(",-1.25,usd").contains("\"Desk, \"\"big\"\" one\"");  // quoted, quotes doubled
    }

    @Test
    void theRevenueEndpoints_areAdminOnly() throws Exception {
        for (String path : new String[] {"/api/admin/revenue", "/api/admin/revenue/entries", "/api/admin/revenue/entries/export"}) {
            mockMvc.perform(as(get(path), seller)).andExpect(status().isForbidden());
            mockMvc.perform(as(get(path), buyer)).andExpect(status().isForbidden());
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(as(get(path), admin)).andExpect(status().isOk());
        }
    }

    // ---- backfill ----

    @Test
    void theBackfillBooksEarlierFeatureFees_onceAndOnlyForSucceededOnes() throws Exception {
        Listing lamp = activeListing("Desk lamp", "12.00");
        Payment booked = featurePayment(lamp, PaymentStatus.SUCCEEDED, "cs_a", "2026-09-01T06:00:00Z");
        Payment missing1 = featurePayment(lamp, PaymentStatus.SUCCEEDED, "cs_b", "2026-09-10T06:00:00Z");
        Payment missing2 = featurePayment(lamp, PaymentStatus.SUCCEEDED, "cs_c", "2026-09-20T06:00:00Z");
        featurePayment(lamp, PaymentStatus.PENDING, "cs_d", "2026-09-21T06:00:00Z");
        featurePayment(lamp, PaymentStatus.REFUNDED, "cs_e", "2026-09-22T06:00:00Z");
        featurePayment(lamp, PaymentStatus.FAILED, "cs_f", "2026-09-23T06:00:00Z");
        Payment deposit = paymentRepository.save(Payment.builder().user(buyer).listing(lamp).amount(50L).currency("usd")
                .purpose(PaymentPurpose.RESERVE_ITEM).status(PaymentStatus.SUCCEEDED).stripeSessionId("cs_g").build());
        ledgerRepository.save(LedgerEntry.builder().type(LedgerType.FEATURE_FEE).amountCents(50L).currency("usd")
                .paymentId(booked.getId()).occurredAt(Instant.parse("2026-09-01T06:00:00Z")).build());

        assertThat(backfill.backfillFeatureFees()).isEqualTo(2);
        assertThat(backfill.backfillFeatureFees()).as("second run").isZero();
        backfill.run(null);                                       // as at every startup
        assertThat(backfill.backfillFeatureFees()).isZero();

        List<LedgerEntry> entries = ledgerRepository.findAll();
        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(LedgerEntry::getPaymentId).containsExactlyInAnyOrder(booked.getId(), missing1.getId(), missing2.getId());
        assertThat(entries).extracting(LedgerEntry::getType).containsOnly(LedgerType.FEATURE_FEE);
        // Dated when each fee was paid, and tied to the listing and payer.
        LedgerEntry entry = entries.stream().filter(e -> e.getPaymentId().equals(missing1.getId())).findFirst().orElseThrow();
        assertThat(entry.getOccurredAt()).isEqualTo(Instant.parse("2026-09-10T06:00:00Z"));
        assertThat(entry.getUserId()).isEqualTo(seller.getId());
        assertThat(entry.getListingTitle()).isEqualTo("Desk lamp");
        assertThat(entries).noneMatch(e -> deposit.getId().equals(e.getPaymentId()));
        // ...and it shows up in the report.
        getReport("2026-09-01", "2026-09-30", "month").andExpect(jsonPath("$.totals.FEATURE_FEE").value(150));
    }

    // ---- helpers ----

    private Payment featurePayment(Listing listing, PaymentStatus status, String session, String paidAt) {
        Payment p = paymentRepository.save(Payment.builder().user(seller).listing(listing).amount(50L).currency("usd")
                .purpose(PaymentPurpose.FEATURE_LISTING).status(status).stripeSessionId(session).build());
        jdbc.update("update payments set created_at = ? where id = ?", Timestamp.from(Instant.parse(paidAt)), p.getId());
        return paymentRepository.findById(p.getId()).orElseThrow();
    }

    private void book(LedgerType type, long cents, String at) {
        book(type, cents, at, "Item");
    }

    private void book(LedgerType type, long cents, String at, String title) {
        ledgerRepository.save(LedgerEntry.builder().type(type).amountCents(cents).currency("usd")
                .listingTitle(title).userId(seller.getId()).occurredAt(Instant.parse(at)).build());
    }

    private void saveOrder(String title, PaymentMethod method, OrderStatus status, PayoutStatus payout, long price, long deposit) {
        orderRepository.save(Order.builder().listingTitle(title).buyer(buyer).seller(seller).itemPriceCents(price)
                .depositCents(deposit).balanceCents(price - deposit).currency("usd").paymentMethod(method)
                .status(status).payoutStatus(payout).build());
    }

    private ResultActions getReport(String from, String to, String groupBy) throws Exception {
        return mockMvc.perform(as(get("/api/admin/revenue").param("from", from).param("to", to).param("groupBy", groupBy), admin));
    }

    private void expire(Listing listing) {
        Listing l = listingRepository.findById(listing.getId()).orElseThrow();
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l);
    }

    private void reserve(Listing listing, User user) throws Exception {
        completePayment(checkoutSession(user, listing, "RESERVE_ITEM"));
    }

    private Listing activeListing(String title, String price) {
        return listingRepository.save(Listing.builder().title(title).price(new BigDecimal(price))
                .category(Category.OTHER).status(ListingStatus.ACTIVE).owner(seller).build());
    }

    private ResultActions createListing(User user, String price) throws Exception {
        return mockMvc.perform(as(post("/api/listings"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Lamp\",\"price\":" + price + ",\"category\":\"OTHER\"}"));
    }

    private long createdId(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private String postingCheckout(User user, long id) throws Exception {
        return sessionOf(checkout(user, id, "POSTING_FEE"));
    }

    private String checkoutSession(User user, Listing listing, String purpose) throws Exception {
        return sessionOf(checkout(user, listing.getId(), purpose));
    }

    private ResultActions checkout(User user, long listingId, String purpose) throws Exception {
        return mockMvc.perform(as(post("/api/payments/checkout-session"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":" + listingId + ",\"purpose\":\"" + purpose + "\"}")).andExpect(status().isCreated());
    }

    private String sessionOf(ResultActions result) throws Exception {
        JsonNode json = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        return json.get("sessionId").asText();
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

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwtService.generateAccessToken(UserPrincipal.from(user)));
    }

    private User saveUser(String email, String name, Role role) {
        return userRepository.save(User.builder().fullName(name).email(email).password("x").role(role).enabled(true).build());
    }
}
