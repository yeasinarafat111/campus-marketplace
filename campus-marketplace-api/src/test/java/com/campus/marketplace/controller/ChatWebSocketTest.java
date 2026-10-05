package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A real WebSocket + STOMP client against a running server: authentication at CONNECT, and live pushes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private JwtService jwtService;

    private final RestTemplate rest = new RestTemplate();
    private User seller;
    private User buyer;
    private User stranger;
    private Listing lamp;
    private WebSocketStompClient client;

    @BeforeEach
    void setUp() {
        cleanUp();
        seller = saveUser("seller@seu.edu.bd", "Sally Seller");
        buyer = saveUser("buyer@seu.edu.bd", "Bob Buyer");
        stranger = saveUser("stranger@seu.edu.bd", "Sam Stranger");
        lamp = listingRepository.save(Listing.builder().title("Desk lamp").price(new BigDecimal("12.00"))
                .category(Category.OTHER).owner(seller).build());
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void cleanUp() {
        if (client != null) {
            client.stop();
        }
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- authentication at CONNECT ----

    @Test
    void aValidAccessToken_connects() throws Exception {
        StompSession session = connect(token(buyer));
        assertThat(session.isConnected()).isTrue();
    }

    @Test
    void connectingWithoutAToken_isRejected() {
        assertRejected(() -> connect(null));
    }

    @Test
    void aGarbageToken_isRejected() {
        assertRejected(() -> connect("not-a-jwt"));
    }

    @Test
    void aRefreshToken_isRejected_onlyAccessTokensAreAccepted() {
        assertRejected(() -> connect(jwtService.generateRefreshToken(UserPrincipal.from(buyer))));
    }

    @Test
    void anExpiredOrTamperedToken_isRejected() {
        String valid = token(buyer);
        assertRejected(() -> connect(valid.substring(0, valid.length() - 3) + "abc"));
    }

    @Test
    void aTokenIssuedBeforeAPasswordChange_isRejected() {
        String token = token(buyer);
        buyer.setPasswordChangedAt(Instant.now().plusSeconds(5));
        userRepository.save(buyer);
        assertRejected(() -> connect(token));
    }

    @Test
    void aDeactivatedAccount_isRejected() {
        String token = token(buyer);
        buyer.setActive(false);
        userRepository.save(buyer);
        assertRejected(() -> connect(token));
    }

    @Test
    void aBrowserOriginThatIsNotAllowed_cannotEvenHandshake() {
        assertRejected(() -> connect(token(buyer), "https://evil.example"));
        // ...while an allowed origin works.
    }

    @Test
    void anAllowedOrigin_connects() throws Exception {
        assertThat(connect(token(buyer), "http://localhost:5173").isConnected()).isTrue();
    }

    @Test
    void aClientCannotSubscribeOutsideItsOwnUserQueue_orSend() throws Exception {
        // Subscribing to a shared broker destination is refused: the server closes the session.
        CompletableFuture<Void> closed = new CompletableFuture<>();
        StompSession second = connect(token(buyer), null, new StompSessionHandlerAdapter() {
            @Override public void handleTransportError(StompSession s, Throwable e) { closed.complete(null); }
        });
        second.subscribe("/queue/messages", frameHandler(new LinkedBlockingQueue<>()));
        closed.get(5, TimeUnit.SECONDS);
        assertThat(second.isConnected()).isFalse();

        StompSession third = connect(token(buyer));
        third.send("/app/anything", "hello");
        awaitDisconnect(third);
    }

    // ---- live pushes ----

    @Test
    void aMessageIsPushedLiveToBothPeople_andTheRecipientGetsTheirUnreadCount() throws Exception {
        long conversation = openConversation(buyer);
        BlockingQueue<Map<String, Object>> sellerMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> sellerUnread = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> buyerMessages = new LinkedBlockingQueue<>();
        StompSession sellerSession = connect(token(seller));
        sellerSession.subscribe("/user/queue/messages", frameHandler(sellerMessages));
        sellerSession.subscribe("/user/queue/unread", frameHandler(sellerUnread));
        StompSession buyerSession = connect(token(buyer));
        buyerSession.subscribe("/user/queue/messages", frameHandler(buyerMessages));
        Thread.sleep(300); // let the subscriptions register

        postMessage(buyer, conversation, "Is it still available?");

        Map<String, Object> pushedToSeller = sellerMessages.poll(5, TimeUnit.SECONDS);
        assertThat(pushedToSeller).isNotNull();
        assertThat(pushedToSeller.get("body")).isEqualTo("Is it still available?");
        assertThat(((Number) pushedToSeller.get("senderId")).longValue()).isEqualTo(buyer.getId());
        assertThat(buyerMessages.poll(5, TimeUnit.SECONDS)).as("the sender's own tabs get it too").isNotNull();
        Map<String, Object> unread = sellerUnread.poll(5, TimeUnit.SECONDS);
        assertThat(unread).isNotNull();
        assertThat(((Number) unread.get("count")).longValue()).isEqualTo(1);
    }

    @Test
    void pushesAreAddressedToTheRightUserOnly() throws Exception {
        long conversation = openConversation(buyer);
        BlockingQueue<Map<String, Object>> strangerMessages = new LinkedBlockingQueue<>();
        StompSession strangerSession = connect(token(stranger));
        strangerSession.subscribe("/user/queue/messages", frameHandler(strangerMessages));
        BlockingQueue<Map<String, Object>> sellerMessages = new LinkedBlockingQueue<>();
        StompSession sellerSession = connect(token(seller));
        sellerSession.subscribe("/user/queue/messages", frameHandler(sellerMessages));
        Thread.sleep(300);

        postMessage(buyer, conversation, "private");

        assertThat(sellerMessages.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(strangerMessages.poll(1, TimeUnit.SECONDS)).as("a non-participant receives nothing").isNull();
    }

    @Test
    void markingReadPushesAReceiptToTheOtherSide() throws Exception {
        long conversation = openConversation(buyer);
        postMessage(buyer, conversation, "hello");
        BlockingQueue<Map<String, Object>> receipts = new LinkedBlockingQueue<>();
        StompSession buyerSession = connect(token(buyer));
        buyerSession.subscribe("/user/queue/read", frameHandler(receipts));
        Thread.sleep(300);

        rest.postForEntity(url("/api/conversations/" + conversation + "/read"), new HttpEntity<>(auth(seller)), String.class);

        Map<String, Object> receipt = receipts.poll(5, TimeUnit.SECONDS);
        assertThat(receipt).isNotNull();
        assertThat(((Number) receipt.get("conversationId")).longValue()).isEqualTo(conversation);
    }

    // ---- helpers ----

    private StompSession connect(String token) throws Exception {
        return connect(token, null, new StompSessionHandlerAdapter() { });
    }

    private StompSession connect(String token, String origin) throws Exception {
        return connect(token, origin, new StompSessionHandlerAdapter() { });
    }

    private StompSession connect(String token, String origin, StompSessionHandlerAdapter handler) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        return client.connectAsync("ws://localhost:" + port + "/ws", handshake, connectHeaders, handler)
                .get(5, TimeUnit.SECONDS);
    }

    /** The connection must fail: the handshake is refused, or the server answers CONNECT with an ERROR and closes. */
    private void assertRejected(ThrowingRunnable connect) {
        assertThatThrownBy(connect::run).satisfiesAnyOf(
                e -> assertThat(e).isInstanceOf(TimeoutException.class),
                e -> assertThat(e).isInstanceOf(java.util.concurrent.ExecutionException.class),
                e -> assertThat(e).hasMessageContaining("Connection closed"));
    }

    private void awaitDisconnect(StompSession session) throws InterruptedException {
        for (int i = 0; i < 50 && session.isConnected(); i++) {
            Thread.sleep(100);
        }
        assertThat(session.isConnected()).isFalse();
    }

    private StompFrameHandler frameHandler(BlockingQueue<Map<String, Object>> into) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @Override @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) { into.add((Map<String, Object>) payload); }
        };
    }

    private long openConversation(User user) throws Exception {
        HttpHeaders headers = auth(user);
        String body = rest.postForObject(url("/api/conversations"),
                new HttpEntity<>("{\"listingId\":" + lamp.getId() + "}", headers), String.class);
        JsonNode json = objectMapper.readTree(body);
        return json.get("id").asLong();
    }

    private void postMessage(User user, long conversation, String text) throws Exception {
        rest.postForObject(url("/api/conversations/" + conversation + "/messages"),
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("body", text)), auth(user)), String.class);
    }

    private HttpHeaders auth(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(user));
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String token(User user) {
        return jwtService.generateAccessToken(UserPrincipal.from(user));
    }

    private User saveUser(String email, String name) {
        return userRepository.save(User.builder().fullName(name).email(email).password("x")
                .role(Role.STUDENT).enabled(true).build());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
