package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.MeetupSpot;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.PayoutStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.OrderAutoCompleteJob;
import com.campus.marketplace.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** After the sale: the buyer confirms receipt or reports a problem, 72-hour auto-complete, and payout rules. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderLifecycleTest {

    private static final String SELLER_PHONE = "+8801711111111";
    private static final String BUYER_PHONE = "+8801722222222";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private OrderService orderService;
    @Autowired private OrderAutoCompleteJob job;

    private User seller;
    private User buyer;
    private User stranger;
    private User admin;
    private Order order;

    @BeforeEach
    void setUp() {
        cleanUp();
        seller = saveUser("seller@seu.edu.bd", "Sally Seller", SELLER_PHONE, Role.STUDENT);
        buyer = saveUser("buyer@seu.edu.bd", "Bob Buyer", BUYER_PHONE, Role.STUDENT);
        stranger = saveUser("stranger@seu.edu.bd", "Sam Stranger", "+8801733333333", Role.STUDENT);
        admin = saveUser("admin@seu.edu.bd", "Ada Admin", null, Role.ADMIN);
        order = saveOrder(MeetupSpot.LIBRARY);
    }

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
        paymentRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- confirm receipt ----

    @Test
    void onlyTheBuyerCanConfirm() throws Exception {
        for (User not : new User[] {seller, stranger, admin}) {
            mockMvc.perform(as(post("/api/orders/{id}/confirm", order.getId()), not)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/orders/{id}/confirm", order.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(as(post("/api/orders/{id}/confirm", 999_999), buyer)).andExpect(status().isNotFound());
        assertThat(reload().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void confirming_completesTheOrder_andSetsCompletedAt() throws Exception {
        mockMvc.perform(as(post("/api/orders/{id}/confirm", order.getId()), buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.autoCompleteAt").doesNotExist());

        Order done = reload();
        assertThat(done.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(done.getCompletedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void confirmingTwice_isRefused_andKeepsTheFirstCompletionTime() throws Exception {
        mockMvc.perform(as(post("/api/orders/{id}/confirm", order.getId()), buyer)).andExpect(status().isOk());
        Instant first = reload().getCompletedAt();

        mockMvc.perform(as(post("/api/orders/{id}/confirm", order.getId()), buyer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You've already confirmed this order"));

        assertThat(reload().getCompletedAt()).isEqualTo(first);
    }

    // ---- report a problem ----

    @Test
    void onlyTheBuyerCanReport_andTheReasonIsValidated() throws Exception {
        for (User not : new User[] {seller, stranger, admin}) {
            report(not, "x").andExpect(status().isForbidden());
        }
        report(buyer, "   ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.reason").exists());
        report(buyer, "x".repeat(1001)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").value("Description must be at most 1000 characters"));
        assertThat(reload().getProblemReportedAt()).isNull();
    }

    @Test
    void aReportFreezesTheOrder_itCantBeConfirmedOrPaidOut_andBothSidesSeeIt() throws Exception {
        report(buyer, "The lamp arrived broken").andExpect(status().isOk())
                .andExpect(jsonPath("$.problemReportedAt").isNotEmpty())
                .andExpect(jsonPath("$.problemReason").value("The lamp arrived broken"))
                .andExpect(jsonPath("$.autoCompleteAt").doesNotExist())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(as(post("/api/orders/{id}/confirm", order.getId()), buyer)).andExpect(status().isConflict());
        report(buyer, "again").andExpect(status().isConflict());
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This order has a problem report open and can't be paid out yet"));

        // The seller knows there's a report but doesn't get the buyer's text.
        mockMvc.perform(as(get("/api/orders/sales"), seller))
                .andExpect(jsonPath("$.content[0].problemReportedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].problemReason").doesNotExist());
    }

    @Test
    void aCompletedOrderCantBeReported() throws Exception {
        mockMvc.perform(as(post("/api/orders/{id}/confirm", order.getId()), buyer)).andExpect(status().isOk());
        report(buyer, "too late").andExpect(status().isConflict());
    }

    // ---- 72-hour auto-complete ----

    @Test
    void autoComplete_waitsFull72Hours() {
        Order justUnder = saveOrderPaidAgo(Duration.ofHours(72).minusMinutes(1));
        Order justOver = saveOrderPaidAgo(Duration.ofHours(72).plusMinutes(1));
        Order fresh = saveOrderPaidAgo(Duration.ofHours(5));

        assertThat(orderService.autoCompleteDue(Instant.now())).isEqualTo(1);

        assertThat(statusOf(justOver)).isEqualTo(OrderStatus.COMPLETED);
        assertThat(completedAt(justOver)).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(statusOf(justUnder)).isEqualTo(OrderStatus.PAID);
        assertThat(statusOf(fresh)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void autoComplete_skipsReportedOrders_andLeavesCompletedOnesAlone() throws Exception {
        Order reported = saveOrderPaidAgo(Duration.ofHours(100));
        Order alreadyDone = saveOrderPaidAgo(Duration.ofHours(100));
        Instant earlier = Instant.now().minus(Duration.ofHours(20)).truncatedTo(ChronoUnit.MILLIS);
        alreadyDone.setStatus(OrderStatus.COMPLETED);
        alreadyDone.setCompletedAt(earlier);
        orderRepository.save(alreadyDone);
        mockMvc.perform(as(post("/api/orders/{id}/report", reported.getId()), buyer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"not what I ordered\"}")).andExpect(status().isOk());

        assertThat(orderService.autoCompleteDue(Instant.now())).isZero();

        assertThat(statusOf(reported)).isEqualTo(OrderStatus.PAID);
        assertThat(completedAt(alreadyDone)).isEqualTo(earlier);
    }

    @Test
    void autoComplete_isIdempotent_andTheScheduledJobRunsItWithoutATransaction() {
        saveOrderPaidAgo(Duration.ofHours(80));
        job.sweep(); // as the scheduler calls it
        assertThat(orderRepository.findAll()).filteredOn(o -> o.getStatus() == OrderStatus.COMPLETED).hasSize(1);
        assertThat(orderService.autoCompleteDue(Instant.now())).isZero();
    }

    @Test
    void theResponseTellsTheBuyerWhenTheOrderWillAutoComplete() throws Exception {
        mockMvc.perform(as(get("/api/orders/purchases"), buyer))
                .andExpect(jsonPath("$.content[0].status").value("PAID"))
                .andExpect(jsonPath("$.content[0].autoCompleteAt").isNotEmpty());
        Instant at = Instant.parse(mockMvc.perform(as(get("/api/orders/purchases"), buyer)).andReturn().getResponse()
                .getContentAsString().replaceAll(".*\"autoCompleteAt\":\"([^\"]+)\".*", "$1"));
        assertThat(at).isCloseTo(order.getCreatedAt().plus(Duration.ofHours(72)), within(1, ChronoUnit.SECONDS));
    }

    // ---- payout ----

    @Test
    void payout_isOnlyForCompletedOrders() throws Exception {
        // PAID: refused.
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a completed order can be paid out"));
        assertThat(reload().getPayoutStatus()).isEqualTo(PayoutStatus.PENDING);

        // COMPLETED: allowed, and idempotent. Paying out doesn't change the order's status.
        mockMvc.perform(as(post("/api/orders/{id}/confirm", order.getId()), buyer)).andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.payoutStatus").value("PAID_OUT"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }
    }

    @Test
    void payout_isRefusedForACancelledOrder_andForNonAdmins() throws Exception {
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), admin)).andExpect(status().isConflict());
        for (User not : new User[] {seller, buyer, stranger}) {
            mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), not)).andExpect(status().isForbidden());
        }
    }

    // ---- admin queue and resolution ----

    @Test
    void theAdminQueue_listsOnlyOpenReports_andResolvingUnfreezesOrCancels() throws Exception {
        Order other = saveOrder(null);
        report(buyer, "broken").andExpect(status().isOk());

        mockMvc.perform(as(get("/api/admin/orders").param("reported", "true"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(order.getId()))
                .andExpect(jsonPath("$.content[0].problemReason").value("broken"))
                .andExpect(jsonPath("$.content[0].buyer.phone").value(BUYER_PHONE));
        mockMvc.perform(as(get("/api/admin/orders"), admin)).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(as(get("/api/admin/orders").param("reported", "true"), buyer)).andExpect(status().isForbidden());

        // Resolve as "the item arrived": completed, payable, and out of the queue.
        mockMvc.perform(as(patch("/api/admin/orders/{id}/resolve", order.getId()).param("outcome", "COMPLETE"), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(as(get("/api/admin/orders").param("reported", "true"), admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", order.getId()), admin)).andExpect(status().isOk());
        // Nothing left to resolve.
        mockMvc.perform(as(patch("/api/admin/orders/{id}/resolve", order.getId()).param("outcome", "CANCEL"), admin))
                .andExpect(status().isConflict());
        // An unreported order has no problem to resolve.
        mockMvc.perform(as(patch("/api/admin/orders/{id}/resolve", other.getId()).param("outcome", "CANCEL"), admin))
                .andExpect(status().isConflict());

        // Cancel outcome.
        Order third = saveOrder(null);
        mockMvc.perform(as(post("/api/orders/{id}/report", third.getId()), buyer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"never came\"}")).andExpect(status().isOk());
        mockMvc.perform(as(patch("/api/admin/orders/{id}/resolve", third.getId()).param("outcome", "CANCEL"), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(as(patch("/api/admin/orders/{id}/payout", third.getId()), admin)).andExpect(status().isConflict());
    }

    // ---- who sees the phone and the meetup spot ----

    @Test
    void bothSidesSeeTheOtherPhoneAndTheMeetupSpot_nobodyElseSeesTheOrder() throws Exception {
        mockMvc.perform(as(get("/api/orders/purchases"), buyer))
                .andExpect(jsonPath("$.content[0].meetupSpot").value("LIBRARY"))
                .andExpect(jsonPath("$.content[0].counterparty.phone").value(SELLER_PHONE));
        mockMvc.perform(as(get("/api/orders/sales"), seller))
                .andExpect(jsonPath("$.content[0].meetupSpot").value("LIBRARY"))
                .andExpect(jsonPath("$.content[0].counterparty.phone").value(BUYER_PHONE));

        for (User not : new User[] {stranger, admin}) {
            String body = mockMvc.perform(as(get("/api/orders/purchases"), not)).andReturn().getResponse().getContentAsString()
                    + mockMvc.perform(as(get("/api/orders/sales"), not)).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("\"totalElements\":0").doesNotContain(SELLER_PHONE).doesNotContain(BUYER_PHONE).doesNotContain("LIBRARY");
        }
    }

    @Test
    void theMeetupSpotIsCopiedOntoTheOrder_soEditingOrDeletingTheListingDoesntChangeIt() throws Exception {
        Listing listing = listingRepository.findById(order.getListing().getId()).orElseThrow();
        listing.setMeetupSpot(MeetupSpot.CAFETERIA);
        listingRepository.save(listing);
        mockMvc.perform(as(get("/api/orders/purchases"), buyer)).andExpect(jsonPath("$.content[0].meetupSpot").value("LIBRARY"));

        listingRepository.deleteById(listing.getId());
        mockMvc.perform(as(get("/api/orders/purchases"), buyer)).andExpect(jsonPath("$.content[0].meetupSpot").value("LIBRARY"));
    }

    // ---- helpers ----

    private org.springframework.test.web.servlet.ResultActions report(User user, String reason) throws Exception {
        return mockMvc.perform(as(post("/api/orders/{id}/report", order.getId()), user).contentType(MediaType.APPLICATION_JSON)
                .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("reason", reason))));
    }

    private Order saveOrder(MeetupSpot spot) {
        Listing listing = listingRepository.save(Listing.builder().title("Desk lamp").price(new BigDecimal("12.00"))
                .category(Category.OTHER).status(ListingStatus.SOLD).meetupSpot(spot).owner(seller).buyer(buyer).build());
        return orderRepository.save(Order.builder().listing(listing).listingTitle("Desk lamp").buyer(buyer).seller(seller)
                .itemPriceCents(1200L).depositCents(50L).balanceCents(1150L).currency("usd").meetupSpot(spot).build());
    }

    private Order saveOrderPaidAgo(Duration ago) {
        Order o = saveOrder(null);
        jdbc.update("update orders set created_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minus(ago)), o.getId());
        return orderRepository.findById(o.getId()).orElseThrow();
    }

    private Order reload() {
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private OrderStatus statusOf(Order o) {
        return orderRepository.findById(o.getId()).orElseThrow().getStatus();
    }

    private Instant completedAt(Order o) {
        return orderRepository.findById(o.getId()).orElseThrow().getCompletedAt();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwtService.generateAccessToken(UserPrincipal.from(user)));
    }

    private User saveUser(String email, String name, String phone, Role role) {
        return userRepository.save(User.builder().fullName(name).email(email).phone(phone).password("x")
                .role(role).enabled(true).build());
    }
}
