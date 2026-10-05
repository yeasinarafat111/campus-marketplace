package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.Report;
import com.campus.marketplace.entity.ReportReason;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.LedgerEntryRepository;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.ReportRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminKpiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private LedgerEntryRepository ledgerRepository;
    @Autowired private JwtService jwtService;

    private User admin;
    private User seller;
    private User student;

    @BeforeEach
    void setUp() {
        ledgerRepository.deleteAll();
        reportRepository.deleteAll();
        orderRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
        admin = user("admin@seu.edu.bd", Role.ADMIN, true, true);
        seller = user("seller@seu.edu.bd", Role.STUDENT, true, true);
        student = user("student@seu.edu.bd", Role.STUDENT, true, true);
        user("unverified@seu.edu.bd", Role.STUDENT, false, true);
        user("banned@seu.edu.bd", Role.STUDENT, true, false);
    }

    private User user(String email, Role role, boolean enabled, boolean active) {
        return userRepository.save(User.builder().fullName("U").email(email).password("x").role(role).enabled(enabled).active(active).build());
    }

    private String token(User u) {
        return jwtService.generateAccessToken(UserPrincipal.from(u));
    }

    @Test
    void countsEverythingTheDashboardShows() throws Exception {
        Listing active = listing(ListingStatus.ACTIVE);
        listing(ListingStatus.ACTIVE);
        listing(ListingStatus.SOLD);
        listing(ListingStatus.PENDING_PAYMENT);
        order(OrderStatus.PAID, null);
        order(OrderStatus.PAID, Instant.now());      // open problem
        order(OrderStatus.COMPLETED, Instant.now()); // a closed one doesn't count as open
        reportRepository.save(Report.builder().listing(active).listingTitle("x").reporter(student).reportedUser(seller)
                .reason(ReportReason.SCAM).build());

        mockMvc.perform(get("/api/admin/kpis").header("Authorization", "Bearer " + token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").value(3))
                .andExpect(jsonPath("$.activeListings").value(2))
                .andExpect(jsonPath("$.orders").value(3))
                .andExpect(jsonPath("$.openOrderProblems").value(1))
                .andExpect(jsonPath("$.openReports").value(1))
                .andExpect(jsonPath("$.netRevenueThisWeekCents").value(0))
                .andExpect(jsonPath("$.currency").exists())
                .andExpect(jsonPath("$.weekFrom").exists());
    }

    @Test
    void isAdminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/kpis")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/kpis").header("Authorization", "Bearer " + token(student))).andExpect(status().isForbidden());
    }

    private Listing listing(ListingStatus status) {
        return listingRepository.save(Listing.builder().title("L").price(new BigDecimal("10.00")).category(Category.OTHER)
                .status(status).owner(seller).build());
    }

    private void order(OrderStatus status, Instant problemAt) {
        orderRepository.save(Order.builder().listingTitle("L").buyer(student).seller(seller).itemPriceCents(1000L)
                .depositCents(50L).balanceCents(950L).currency("usd").status(status).problemReportedAt(problemAt).build());
    }
}
