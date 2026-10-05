package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.ReportStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.ReportRepository;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReportsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ListingRepository listingRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private User seller;
    private User alice;
    private User carol;
    private User admin;
    private String sellerToken;
    private String aliceToken;
    private String carolToken;
    private String adminToken;
    private Listing listing;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
        seller = user("seller@seu.edu.bd", "Seller", Role.STUDENT);
        alice = user("alice@seu.edu.bd", "Alice", Role.STUDENT);
        carol = user("carol@seu.edu.bd", "Carol", Role.STUDENT);
        admin = user("admin@seu.edu.bd", "Admin", Role.ADMIN);
        sellerToken = token(seller);
        aliceToken = token(alice);
        carolToken = token(carol);
        adminToken = token(admin);
        listing = listingRepository.save(Listing.builder().title("Dodgy laptop").price(new BigDecimal("10.00"))
                .category(Category.ELECTRONICS).status(ListingStatus.ACTIVE).owner(seller).build());
    }

    private User user(String email, String name, Role role) {
        return userRepository.save(User.builder().fullName(name).email(email).password("x").role(role).enabled(true).build());
    }

    private String token(User u) {
        return jwtService.generateAccessToken(UserPrincipal.from(u));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder report(String token, String body) {
        return as(post("/api/listings/{id}/report", listing.getId()), token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder resolve(Long id, String action) {
        return as(post("/api/admin/reports/{id}/resolve", id), adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + action + "\"}");
    }

    private Long firstReportId() {
        return reportRepository.findAll().get(0).getId();
    }

    @Test
    void reporting_createsAnOpenReport_onePerUserPerListing() throws Exception {
        mockMvc.perform(report(aliceToken, "{\"reason\":\"SCAM\",\"details\":\"  Asked me to pay outside the app  \"}"))
                .andExpect(status().isCreated());
        var saved = reportRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(saved.getDetails()).isEqualTo("Asked me to pay outside the app");
        assertThat(saved.getListingTitle()).isEqualTo("Dodgy laptop");

        mockMvc.perform(report(aliceToken, "{\"reason\":\"OTHER\"}")).andExpect(status().isConflict());
        mockMvc.perform(report(carolToken, "{\"reason\":\"OTHER\"}")).andExpect(status().isCreated());
        assertThat(reportRepository.count()).isEqualTo(2);
    }

    @Test
    void reporting_isValidated() throws Exception {
        mockMvc.perform(report(aliceToken, "{}")).andExpect(status().isBadRequest());
        mockMvc.perform(report(aliceToken, "{\"reason\":\"RUDE\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(report(aliceToken, "{\"reason\":\"OTHER\",\"details\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(report(sellerToken, "{\"reason\":\"SCAM\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/listings/{id}/report", listing.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"SCAM\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(post("/api/listings/{id}/report", 999999), aliceToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"SCAM\"}")).andExpect(status().isNotFound());
        assertThat(reportRepository.count()).isZero();
    }

    @Test
    void anUnpublishedListing_cannotBeReported() throws Exception {
        listing.setStatus(ListingStatus.PENDING_PAYMENT);
        listingRepository.save(listing);
        mockMvc.perform(report(aliceToken, "{\"reason\":\"SCAM\"}")).andExpect(status().isNotFound());
    }

    @Test
    void theQueue_isAdminOnly_oldestOpenFirst() throws Exception {
        mockMvc.perform(report(aliceToken, "{\"reason\":\"SCAM\"}")).andExpect(status().isCreated());
        Thread.sleep(5);
        mockMvc.perform(report(carolToken, "{\"reason\":\"WRONG_CATEGORY\"}")).andExpect(status().isCreated());

        mockMvc.perform(as(get("/api/admin/reports"), aliceToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(get("/api/admin/reports"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].reason").value("SCAM"))
                .andExpect(jsonPath("$.content[0].reporter.fullName").value("Alice"))
                .andExpect(jsonPath("$.content[0].reportedUser.fullName").value("Seller"))
                .andExpect(jsonPath("$.content[0].listing.id").value(listing.getId()))
                .andExpect(jsonPath("$.content[0].reporter.email").doesNotExist());
        mockMvc.perform(as(get("/api/admin/reports").param("status", "RESOLVED"), adminToken))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void resolve_withNoAction_closesOnlyThatReport() throws Exception {
        mockMvc.perform(report(aliceToken, "{\"reason\":\"SCAM\"}"));
        mockMvc.perform(report(carolToken, "{\"reason\":\"SCAM\"}"));
        Long id = firstReportId();
        mockMvc.perform(resolve(id, "RESOLVE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED")).andExpect(jsonPath("$.resolution").value("RESOLVE"));
        assertThat(reportRepository.countByStatus(ReportStatus.OPEN)).isEqualTo(1);
        assertThat(listingRepository.existsById(listing.getId())).isTrue();
        mockMvc.perform(resolve(id, "RESOLVE")).andExpect(status().isConflict());
    }

    @Test
    void resolve_deleteListing_removesIt_keepsTheReports_andClosesTheOthers() throws Exception {
        mockMvc.perform(report(aliceToken, "{\"reason\":\"SCAM\"}"));
        mockMvc.perform(report(carolToken, "{\"reason\":\"PROHIBITED_ITEM\"}"));
        mockMvc.perform(resolve(firstReportId(), "DELETE_LISTING")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("DELETE_LISTING")).andExpect(jsonPath("$.listing").isEmpty())
                .andExpect(jsonPath("$.listingTitle").value("Dodgy laptop"));
        assertThat(listingRepository.existsById(listing.getId())).isFalse();
        assertThat(reportRepository.count()).isEqualTo(2);
        assertThat(reportRepository.countByStatus(ReportStatus.OPEN)).isZero();
        mockMvc.perform(as(get("/api/listings/{id}", listing.getId()), adminToken)).andExpect(status().isNotFound());
    }

    @Test
    void resolve_deactivateUser_deactivatesTheOwner_andClosesTheirOtherReports() throws Exception {
        Listing second = listingRepository.save(Listing.builder().title("Fake phone").price(new BigDecimal("10.00"))
                .category(Category.ELECTRONICS).status(ListingStatus.ACTIVE).owner(seller).build());
        mockMvc.perform(report(aliceToken, "{\"reason\":\"SCAM\"}"));
        mockMvc.perform(as(post("/api/listings/{id}/report", second.getId()), carolToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"SCAM\"}"));
        mockMvc.perform(resolve(firstReportId(), "DEACTIVATE_USER")).andExpect(status().isOk());
        assertThat(userRepository.findById(seller.getId()).orElseThrow().isActive()).isFalse();
        assertThat(reportRepository.countByStatus(ReportStatus.OPEN)).isZero();
    }

    @Test
    void resolve_needsAnAdmin_andAValidAction() throws Exception {
        mockMvc.perform(report(aliceToken, "{\"reason\":\"SCAM\"}"));
        Long id = firstReportId();
        mockMvc.perform(as(post("/api/admin/reports/{id}/resolve", id), aliceToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"RESOLVE\"}")).andExpect(status().isForbidden());
        mockMvc.perform(resolve(id, "BAN_EVERYONE")).andExpect(status().isBadRequest());
        mockMvc.perform(resolve(999999L, "RESOLVE")).andExpect(status().isNotFound());
        assertThat(reportRepository.countByStatus(ReportStatus.OPEN)).isEqualTo(1);
    }

    @Test
    void anOwnerDeletingTheListing_leavesTheReportInTheQueue() throws Exception {
        mockMvc.perform(report(aliceToken, "{\"reason\":\"SCAM\"}"));
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/listings/{id}", listing.getId()), sellerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/admin/reports"), adminToken))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].listing").isEmpty())
                .andExpect(jsonPath("$.content[0].listingTitle").value("Dodgy laptop"));
    }
}
