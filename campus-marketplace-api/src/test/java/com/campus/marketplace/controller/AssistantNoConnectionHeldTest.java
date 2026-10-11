package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.service.AssistantGateway;
import com.campus.marketplace.service.FakeAssistantGateway;
import com.campus.marketplace.service.ListingPhotoFetcher;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.campus.marketplace.controller.AssistantTestSupport.ask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The slow parts of an assistant request (fetching the photo, waiting up to 45 s for Gemini) must not hold a
 * database connection: the pool is small (Supabase allows 15 clients in all). The listing is read and mapped to a
 * DTO in a short read-only transaction that has ended before either starts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantNoConnectionHeldTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired ListingRepository listings;
    @Autowired JwtService jwt;
    @Autowired FakeAssistantGateway fake;
    @Autowired DataSource dataSource;
    @MockitoBean ListingPhotoFetcher photoFetcher;

    Listing listing;
    String token;

    @BeforeEach
    void setUp() {
        listings.deleteAll();
        users.deleteAll();
        fake.reset();
        listing = AssistantTestSupport.listing(listings, AssistantTestSupport.seller(users), "Phone", "Samsung A54", "45.00");
        token = AssistantTestSupport.token(jwt, AssistantTestSupport.buyer(users));
    }

    private int activeConnections() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void noTransactionAndNoConnection_duringThePhotoFetchOrTheGeminiCall() throws Exception {
        AtomicReference<Boolean> txDuringFetch = new AtomicReference<>();
        AtomicReference<Integer> connectionsDuringFetch = new AtomicReference<>();
        when(photoFetcher.fetch(any())).thenAnswer(inv -> {
            txDuringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
            connectionsDuringFetch.set(activeConnections());
            return Optional.of(new AssistantGateway.Image("image/jpeg", new byte[]{1}));
        });
        AtomicReference<Boolean> txDuringCall = new AtomicReference<>();
        AtomicReference<Integer> connectionsDuringCall = new AtomicReference<>();
        fake.onNextCall(() -> {
            txDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            connectionsDuringCall.set(activeConnections());
        });

        mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}")).andExpect(status().isOk());

        assertThat(txDuringFetch.get()).as("transaction active during the photo fetch").isFalse();
        assertThat(connectionsDuringFetch.get()).as("connections checked out during the photo fetch").isZero();
        assertThat(txDuringCall.get()).as("transaction active during the Gemini call").isFalse();
        assertThat(connectionsDuringCall.get()).as("connections checked out during the Gemini call").isZero();
        assertThat(fake.lastPrompt().image()).isNotNull();
    }
}
