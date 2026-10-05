package com.campus.marketplace.controller;

import com.campus.marketplace.dto.NotificationResponse;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.NotificationType;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.NotificationRepository;
import com.campus.marketplace.repository.ReportRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.NotificationPushListener;
import com.campus.marketplace.service.NotificationService;
import com.campus.marketplace.service.ReservationReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private NotificationService notificationService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ReservationReminderService reminderService;
    @Autowired private ListingRepository listingRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @MockitoBean private SimpMessagingTemplate messagingTemplate;

    private User alice;
    private User bob;
    private User admin;
    private String aliceToken;
    private String bobToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        notificationRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
        alice = user("alice@seu.edu.bd", "Alice", Role.STUDENT);
        bob = user("bob@seu.edu.bd", "Bob", Role.STUDENT);
        admin = user("admin@seu.edu.bd", "Admin", Role.ADMIN);
        aliceToken = jwtService.generateAccessToken(UserPrincipal.from(alice));
        bobToken = jwtService.generateAccessToken(UserPrincipal.from(bob));
        adminToken = jwtService.generateAccessToken(UserPrincipal.from(admin));
    }

    private User user(String email, String name, Role role) {
        return userRepository.save(User.builder().fullName(name).email(email).password("x").role(role).enabled(true).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.header("Authorization", "Bearer " + token);
    }

    private void make(User to, String title) {
        notificationService.notify(to.getId(), NotificationType.RESERVED, title, "body", "/listings/1");
    }

    @Test
    void list_isNewestFirst_andPerUser_withAnUnreadCount() throws Exception {
        make(alice, "First");
        make(alice, "Second");
        make(bob, "Bob's");

        mockMvc.perform(as(get("/api/notifications"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Second"))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[0].link").value("/listings/1"))
                .andExpect(jsonPath("$.content[1].title").value("First"));
        mockMvc.perform(as(get("/api/notifications/unread-count"), aliceToken)).andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(as(get("/api/notifications/unread-count"), bobToken)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markRead_isIdempotent_andOnlyForTheOwner() throws Exception {
        make(alice, "Hello");
        Long id = notificationRepository.findAll().get(0).getId();

        mockMvc.perform(as(post("/api/notifications/{id}/read", id), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/notifications/unread-count"), aliceToken)).andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(as(post("/api/notifications/{id}/read", id), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(post("/api/notifications/{id}/read", id), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/notifications/unread-count"), aliceToken)).andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(as(get("/api/notifications"), aliceToken)).andExpect(jsonPath("$.content[0].read").value(true));
        mockMvc.perform(as(post("/api/notifications/{id}/read", 999999), aliceToken)).andExpect(status().isNotFound());
    }

    @Test
    void readAll_marksOnlyTheCallersNotifications() throws Exception {
        make(alice, "A1");
        make(alice, "A2");
        make(bob, "B1");
        mockMvc.perform(as(post("/api/notifications/read-all"), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/notifications/unread-count"), aliceToken)).andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(as(get("/api/notifications/unread-count"), bobToken)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void requiresLogin_andValidatesPaging() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(get("/api/notifications").param("size", "500"), aliceToken)).andExpect(status().isBadRequest());
    }

    @Test
    void aNewNotification_isPushedToThatUserOnly_afterCommit_withTheUnreadCount() {
        make(alice, "Live");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(eq(alice.getId().toString()), eq("/queue/notifications"), payload.capture());
        var push = (NotificationPushListener.Push) payload.getValue();
        assertThat(push.unread()).isEqualTo(1);
        assertThat(push.notification().title()).isEqualTo("Live");
        assertThat(push.notification()).isInstanceOf(NotificationResponse.class);
        org.mockito.Mockito.verify(messagingTemplate, org.mockito.Mockito.never())
                .convertAndSendToUser(eq(bob.getId().toString()), eq("/queue/notifications"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markingRead_pushesTheNewUnreadCount_soOtherTabsFollow() throws Exception {
        make(alice, "One");
        org.mockito.Mockito.clearInvocations(messagingTemplate);
        mockMvc.perform(as(post("/api/notifications/read-all"), aliceToken)).andExpect(status().isNoContent());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq(alice.getId().toString()), eq("/queue/notifications"), payload.capture());
        var push = (NotificationPushListener.Push) payload.getValue();
        assertThat(push.notification()).isNull();
        assertThat(push.unread()).isZero();
    }

    @Test
    void aFailedPush_neverBreaksTheRequest() {
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down")).when(messagingTemplate)
                .convertAndSendToUser(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        make(alice, "Still saved");
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void notifyOnce_ignoresARepeatOfTheSameKey() {
        assertThat(notificationService.notifyOnce(alice.getId(), NotificationType.RESERVATION_EXPIRING, "t", "b", null, "k1")).isTrue();
        assertThat(notificationService.notifyOnce(alice.getId(), NotificationType.RESERVATION_EXPIRING, "t", "b", null, "k1")).isFalse();
        assertThat(notificationService.notifyOnce(bob.getId(), NotificationType.RESERVATION_EXPIRING, "t", "b", null, "k1")).isTrue();
        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    void reminders_goToBuyersWhoseReservationEndsWithinSixHours_onlyOnce() {
        Listing soon = reserved("Ends soon", Duration.ofHours(5));
        reserved("Plenty of time", Duration.ofHours(20));
        reserved("Already over", Duration.ofMinutes(-10));

        assertThat(reminderService.sendDueReminders()).isEqualTo(1);
        assertThat(reminderService.sendDueReminders()).as("second sweep").isZero();
        assertThat(notificationRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getUser().getId()).isEqualTo(alice.getId());
            assertThat(n.getType()).isEqualTo(NotificationType.RESERVATION_EXPIRING);
            assertThat(n.getLink()).isEqualTo("/listings/" + soon.getId());
            assertThat(n.getBody()).contains("Ends soon");
        });
    }

    @Test
    void aFreshReservationOfTheSameItem_getsItsOwnReminder() {
        Listing listing = reserved("Lamp", Duration.ofHours(4));
        assertThat(reminderService.sendDueReminders()).isEqualTo(1);
        listing.setReservationExpiresAt(Instant.now().plus(Duration.ofHours(3)));
        listingRepository.save(listing);
        assertThat(reminderService.sendDueReminders()).isEqualTo(1);
    }

    @Test
    void resolvingAReport_notifiesEveryReporterWhoseReportClosed() throws Exception {
        Listing listing = listingRepository.save(Listing.builder().title("Fake watch").price(new BigDecimal("10.00"))
                .category(Category.OTHER).status(ListingStatus.ACTIVE).owner(alice).build());
        mockMvc.perform(as(post("/api/listings/{id}/report", listing.getId()), bobToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SCAM\"}")).andExpect(status().isCreated());
        Long reportId = reportRepository.findAll().get(0).getId();

        mockMvc.perform(as(post("/api/admin/reports/{id}/resolve", reportId), adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RESOLVE\"}")).andExpect(status().isOk());

        assertThat(notificationRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getUser().getId()).isEqualTo(bob.getId());
            assertThat(n.getType()).isEqualTo(NotificationType.REPORT_RESOLVED);
            assertThat(n.getBody()).contains("Fake watch");
        });
    }

    private Listing reserved(String title, Duration endsIn) {
        return listingRepository.save(Listing.builder().title(title).price(new BigDecimal("12.00")).category(Category.OTHER)
                .status(ListingStatus.ACTIVE).owner(bob).reservedBy(alice).reservedAt(Instant.now().minus(Duration.ofHours(30)))
                .reservationExpiresAt(Instant.now().plus(endsIn)).build());
    }
}
