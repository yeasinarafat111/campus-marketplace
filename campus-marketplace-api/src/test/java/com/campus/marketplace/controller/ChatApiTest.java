package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Conversation;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ConversationRepository;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.MessageRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chat over REST: who may do what, idempotent creation, unread counts, paging and the rate limit. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private JwtService jwtService;

    private User seller;
    private User buyer;
    private User stranger;
    private Listing lamp;

    @BeforeEach
    void setUp() {
        cleanUp();
        seller = saveUser("seller@seu.edu.bd", "Sally Seller");
        buyer = saveUser("buyer@seu.edu.bd", "Bob Buyer");
        stranger = saveUser("stranger@seu.edu.bd", "Sam Stranger");
        lamp = listingRepository.save(Listing.builder().title("Desk lamp").price(new BigDecimal("12.00"))
                .category(Category.OTHER).imageUrl("https://img.example/lamp.jpg").owner(seller).build());
    }

    @AfterEach
    void cleanUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- create / get-or-create ----

    @Test
    void openingAConversation_isIdempotent() throws Exception {
        long first = id(open(buyer, lamp).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listing.title").value("Desk lamp"))
                .andExpect(jsonPath("$.counterparty.fullName").value("Sally Seller"))
                .andExpect(jsonPath("$.unreadCount").value(0)));

        long again = id(open(buyer, lamp).andExpect(status().isOk()));
        long thirdTime = id(open(buyer, lamp).andExpect(status().isOk()));

        assertThat(again).isEqualTo(first);
        assertThat(thirdTime).isEqualTo(first);
        assertThat(conversationRepository.count()).isEqualTo(1);
    }

    @Test
    void differentBuyersGetSeparateConversations() throws Exception {
        long a = id(open(buyer, lamp));
        long b = id(open(stranger, lamp));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void youCantMessageYourselfAboutYourOwnListing() throws Exception {
        open(seller, lamp).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't message yourself about your own listing"));
        assertThat(conversationRepository.count()).isZero();
    }

    @Test
    void unknownListings_andListingsOfDeactivatedSellers_are404() throws Exception {
        mockMvc.perform(as(post("/api/conversations"), buyer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":999999}")).andExpect(status().isNotFound());
        seller.setActive(false);
        userRepository.save(seller);
        open(buyer, lamp).andExpect(status().isNotFound());
    }

    @Test
    void openingNeedsALoginAndAListingId() throws Exception {
        mockMvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":" + lamp.getId() + "}")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(post("/api/conversations"), buyer).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.listingId").value("listingId is required"));
    }

    // ---- sending ----

    @Test
    void sending_savesTheMessage_andTheOtherSideSeesItUnread() throws Exception {
        long conversation = id(open(buyer, lamp));

        send(buyer, conversation, "  Is it still available?  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Is it still available?"))
                .andExpect(jsonPath("$.senderId").value(buyer.getId()))
                .andExpect(jsonPath("$.readAt").doesNotExist());

        inbox(seller).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].unreadCount").value(1))
                .andExpect(jsonPath("$[0].lastMessage.preview").value("Is it still available?"))
                .andExpect(jsonPath("$[0].lastMessage.mine").value(false))
                .andExpect(jsonPath("$[0].counterparty.fullName").value("Bob Buyer"));
        inbox(buyer).andExpect(jsonPath("$[0].unreadCount").value(0))
                .andExpect(jsonPath("$[0].lastMessage.mine").value(true));
    }

    @Test
    void messageValidation() throws Exception {
        long conversation = id(open(buyer, lamp));
        send(buyer, conversation, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.body").exists());
        send(buyer, conversation, "x".repeat(2001)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.body").value("Message must be at most 2000 characters"));
        send(buyer, conversation, "x".repeat(2000)).andExpect(status().isCreated());
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    @Test
    void messagesAreStoredAsPlainText() throws Exception {
        long conversation = id(open(buyer, lamp));
        send(buyer, conversation, "<script>alert(1)</script>").andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("<script>alert(1)</script>"));
    }

    @Test
    void theRateLimitIs20AMinute_with429AndRetryAfter() throws Exception {
        long conversation = id(open(buyer, lamp));
        for (int i = 0; i < 20; i++) {
            send(buyer, conversation, "msg " + i).andExpect(status().isCreated());
        }
        send(buyer, conversation, "one too many").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // The other person has their own allowance.
        send(seller, conversation, "reply").andExpect(status().isCreated());
        assertThat(messageRepository.count()).isEqualTo(21);
    }

    // ---- reading, paging, unread ----

    @Test
    void threadPaging_usesACursor_oldestFirstWithinAPage() throws Exception {
        long conversation = id(open(buyer, lamp));
        for (int i = 1; i <= 7; i++) {
            send(i % 2 == 0 ? seller : buyer, conversation, "m" + i).andExpect(status().isCreated());
        }

        JsonNode latest = json(messages(buyer, conversation, null, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true)));
        assertThat(latest.get("messages")).extracting(n -> n.get("body").asText()).containsExactly("m5", "m6", "m7");

        long firstId = latest.get("messages").get(0).get("id").asLong();
        JsonNode older = json(messages(buyer, conversation, firstId, 3).andExpect(jsonPath("$.hasMore").value(true)));
        assertThat(older.get("messages")).extracting(n -> n.get("body").asText()).containsExactly("m2", "m3", "m4");

        long oldestId = older.get("messages").get(0).get("id").asLong();
        JsonNode last = json(messages(buyer, conversation, oldestId, 3).andExpect(jsonPath("$.hasMore").value(false)));
        assertThat(last.get("messages")).extracting(n -> n.get("body").asText()).containsExactly("m1");

        messages(buyer, conversation, null, 101).andExpect(status().isBadRequest());
    }

    @Test
    void unreadCounts_andMarkingRead() throws Exception {
        long conversation = id(open(buyer, lamp));
        send(buyer, conversation, "a").andExpect(status().isCreated());
        send(buyer, conversation, "b").andExpect(status().isCreated());
        send(seller, conversation, "c").andExpect(status().isCreated());

        unread(seller).andExpect(jsonPath("$.count").value(2));
        unread(buyer).andExpect(jsonPath("$.count").value(1));
        unread(stranger).andExpect(jsonPath("$.count").value(0));

        mockMvc.perform(as(post("/api/conversations/{id}/read", conversation), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(as(post("/api/conversations/{id}/read", conversation), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0)); // idempotent

        unread(seller).andExpect(jsonPath("$.count").value(0));
        unread(buyer).andExpect(jsonPath("$.count").value(1)); // reading doesn't touch the other side's unread
        // The buyer's two messages now show as read; the seller's reply is still unread.
        JsonNode thread = json(messages(buyer, conversation, null, null));
        assertThat(thread.get("messages").get(0).get("readAt").isNull()).isFalse();
        assertThat(thread.get("messages").get(1).get("readAt").isNull()).isFalse();
        assertThat(thread.get("messages").get(2).get("readAt").isNull()).isTrue();
    }

    @Test
    void inbox_isNewestActivityFirst_andOnlyTheCallers() throws Exception {
        Listing chair = listingRepository.save(Listing.builder().title("Chair").price(new BigDecimal("30.00"))
                .category(Category.FURNITURE).owner(seller).build());
        long lampChat = id(open(buyer, lamp));
        long chairChat = id(open(buyer, chair));
        id(open(stranger, lamp));
        send(buyer, lampChat, "about the lamp").andExpect(status().isCreated());
        send(buyer, chairChat, "about the chair").andExpect(status().isCreated());

        inbox(buyer).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].listing.title").value("Chair"))
                .andExpect(jsonPath("$[1].listing.title").value("Desk lamp"));
        inbox(seller).andExpect(jsonPath("$.length()").value(3));
        inbox(stranger).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aConversationSurvivesItsListingBeingDeleted() throws Exception {
        long conversation = id(open(buyer, lamp));
        send(buyer, conversation, "hello").andExpect(status().isCreated());
        listingRepository.deleteById(lamp.getId());

        inbox(buyer).andExpect(jsonPath("$[0].listing.title").value("Desk lamp"))
                .andExpect(jsonPath("$[0].listing.id").doesNotExist())
                .andExpect(jsonPath("$[0].listing.imageUrl").value("https://img.example/lamp.jpg"));
        messages(buyer, conversation, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1));
    }

    @Test
    void phoneNumbersNeverAppearInChat() throws Exception {
        buyer.setPhone("+8801722222222");
        seller.setPhone("+8801711111111");
        userRepository.save(buyer);
        userRepository.save(seller);
        long conversation = id(open(buyer, lamp));
        send(buyer, conversation, "hi").andExpect(status().isCreated());

        String all = inbox(seller).andReturn().getResponse().getContentAsString()
                + messages(seller, conversation, null, null).andReturn().getResponse().getContentAsString()
                + open(buyer, lamp).andReturn().getResponse().getContentAsString();
        assertThat(all).doesNotContain("+88017").doesNotContain("phone");
    }

    // ---- authorization on every endpoint ----

    @Test
    void onlyTheTwoParticipantsMayUseAConversation() throws Exception {
        long conversation = id(open(buyer, lamp));
        send(buyer, conversation, "hi").andExpect(status().isCreated());

        messages(stranger, conversation, null, null).andExpect(status().isForbidden());
        send(stranger, conversation, "hijack").andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/conversations/{id}/read", conversation), stranger)).andExpect(status().isForbidden());
        // ...and the seller (the other participant) may.
        messages(seller, conversation, null, null).andExpect(status().isOk());
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    @Test
    void unknownConversations_are404() throws Exception {
        messages(buyer, 999_999L, null, null).andExpect(status().isNotFound());
        send(buyer, 999_999L, "hi").andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/conversations/{id}/read", 999_999L), buyer)).andExpect(status().isNotFound());
    }

    @Test
    void everyEndpointNeedsALogin() throws Exception {
        long conversation = id(open(buyer, lamp));
        mockMvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/conversations/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/conversations/{id}/messages", conversation)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/conversations/{id}/messages", conversation).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"x\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/conversations/{id}/read", conversation)).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private ResultActions open(User user, Listing listing) throws Exception {
        return mockMvc.perform(as(post("/api/conversations"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":" + listing.getId() + "}"));
    }

    private ResultActions send(User user, long conversation, String body) throws Exception {
        return mockMvc.perform(as(post("/api/conversations/{id}/messages", conversation), user)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("body", body))));
    }

    private ResultActions messages(User user, long conversation, Long beforeId, Integer size) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/conversations/{id}/messages", conversation);
        if (beforeId != null) request.param("beforeId", String.valueOf(beforeId));
        if (size != null) request.param("size", String.valueOf(size));
        return mockMvc.perform(as(request, user));
    }

    private ResultActions inbox(User user) throws Exception {
        return mockMvc.perform(as(get("/api/conversations"), user)).andExpect(status().isOk());
    }

    private ResultActions unread(User user) throws Exception {
        return mockMvc.perform(as(get("/api/conversations/unread-count"), user)).andExpect(status().isOk());
    }

    private long id(ResultActions result) throws Exception {
        return json(result).get("id").asLong();
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwtService.generateAccessToken(UserPrincipal.from(user)));
    }

    private User saveUser(String email, String name) {
        return userRepository.save(User.builder().fullName(name).email(email).password("x")
                .role(Role.STUDENT).enabled(true).build());
    }
}
