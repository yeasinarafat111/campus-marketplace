package com.campus.marketplace.controller;

import com.campus.marketplace.dto.AssistantResponse;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.campus.marketplace.controller.AssistantTestSupport.ask;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real Gemini gateway against a local stub that fails the way Gemini does. Every failure is a 200 with the
 * contact message, and the listing page's other calls keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantGeminiErrorsTest {

    static final HttpServer STUB;
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicLong DELAY_MS = new AtomicLong();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        STUB.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(DELAY_MS.get());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String body = STATUS.get() == 200
                    ? "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"- RAM: 8 GB\"}]}}]}"
                    : "{\"error\":{\"code\":" + STATUS.get() + ",\"status\":\"RESOURCE_EXHAUSTED\"}}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(STATUS.get(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        STUB.start();
    }

    @DynamicPropertySource
    static void gemini(DynamicPropertyRegistry registry) {
        registry.add("app.assistant.gateway", () -> "gemini");
        registry.add("app.assistant.api-key", () -> "test-key");
        registry.add("app.assistant.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("app.assistant.timeout", () -> "700ms");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired ListingRepository listings;
    @Autowired JwtService jwt;
    Listing listing;
    String token;

    @BeforeEach
    void setUp() {
        STATUS.set(200);
        DELAY_MS.set(0);
        listings.deleteAll();
        users.deleteAll();
        listing = AssistantTestSupport.listing(listings, AssistantTestSupport.seller(users), "Phone", "Samsung A54", "45.00");
        token = AssistantTestSupport.token(jwt, AssistantTestSupport.buyer(users));
    }

    private void expectContactMessage() throws Exception {
        mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").value(true))
                .andExpect(jsonPath("$.answer").value(AssistantResponse.UNAVAILABLE_MESSAGE))
                .andExpect(jsonPath("$.answer").value("Out of tokens, please contact admin. Admin E-mail: 2023100000351@seu.edu.bd"))
                .andExpect(jsonPath("$.productName").value("Phone"));
        // The rest of the listing page is unaffected.
        mockMvc.perform(get("/api/listings/{id}", listing.getId())).andExpect(status().isOk());
    }

    @Test
    void working_answersNormally() throws Exception {
        mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").value(false))
                .andExpect(jsonPath("$.answer").value("- RAM: 8 GB"));
    }

    @Test
    void quotaUsedUp_429_showsTheContactMessage() throws Exception {
        STATUS.set(429);
        expectContactMessage();
    }

    @Test
    void serverError_500_showsTheContactMessage() throws Exception {
        STATUS.set(500);
        expectContactMessage();
    }

    @Test
    void overloaded_503_showsTheContactMessage() throws Exception {
        STATUS.set(503);
        expectContactMessage();
    }

    @Test
    void timeout_showsTheContactMessage() throws Exception {
        DELAY_MS.set(2_000);
        expectContactMessage();
    }

    @Test
    void badKeyOrModel_4xx_showsTheContactMessage() throws Exception {
        STATUS.set(403);
        expectContactMessage();
    }
}
