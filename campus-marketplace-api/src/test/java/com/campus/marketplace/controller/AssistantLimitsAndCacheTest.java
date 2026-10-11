package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.service.AssistantRateLimiter;
import com.campus.marketplace.service.FakeAssistantGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.campus.marketplace.controller.AssistantTestSupport.ask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantLimitsAndCacheTest {

    abstract static class Base {
        @Autowired MockMvc mockMvc;
        @Autowired UserRepository users;
        @Autowired ListingRepository listings;
        @Autowired JwtService jwt;
        @Autowired FakeAssistantGateway fake;
        @Autowired AssistantRateLimiter limiter;
        User seller;
        Listing listing;
        String sellerToken;
        String token;

        @BeforeEach
        void setUp() {
            listings.deleteAll();
            users.deleteAll();
            fake.reset();
            limiter.reset();
            seller = AssistantTestSupport.seller(users);
            listing = AssistantTestSupport.listing(listings, seller, "Lamp", "desk lamp", "10.00");
            sellerToken = AssistantTestSupport.token(jwt, seller);
            token = AssistantTestSupport.token(jwt, AssistantTestSupport.buyer(users));
        }
    }

    /** Unlimited by default: many calls from one user all go through. */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class ByDefault extends Base {
        @Test
        void nothingOnOurSideRefuses() throws Exception {
            for (int i = 0; i < 25; i++) {
                mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"ASK\",\"question\":\"q" + i + "\"}"))
                        .andExpect(status().isOk());
            }
            assertThat(fake.calls()).isEqualTo(25);
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {"app.assistant.per-user-per-hour=2", "app.assistant.global-per-day=3"})
    class WithLimitsConfigured extends Base {
        @Test
        void perUserLimit_is429_withHowLongToWait() throws Exception {
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"ASK\",\"question\":\"a\"}")).andExpect(status().isOk());
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"ASK\",\"question\":\"b\"}")).andExpect(status().isOk());
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"ASK\",\"question\":\"c\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.message").value(containsString("Try again in")));
            assertThat(fake.calls()).isEqualTo(2);
        }

        @Test
        void globalLimit_coversEveryone() throws Exception {
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"ASK\",\"question\":\"a\"}")).andExpect(status().isOk());
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"ASK\",\"question\":\"b\"}")).andExpect(status().isOk());
            mockMvc.perform(ask(listing.getId(), sellerToken, "{\"mode\":\"ASK\",\"question\":\"c\"}")).andExpect(status().isOk());
            mockMvc.perform(ask(listing.getId(), sellerToken, "{\"mode\":\"ASK\",\"question\":\"d\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.message").value(containsString("daily limit")));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {"app.cache.enabled=true", "app.assistant.per-user-per-hour=3"})
    class Caching extends Base {
        @Test
        void specsWithoutNeed_andVerdict_areCached_andFree() throws Exception {
            for (String mode : new String[]{"SPECS", "VERDICT"}) {
                mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"" + mode + "\"}")).andExpect(jsonPath("$.cached").value(false));
                mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"" + mode + "\"}")).andExpect(jsonPath("$.cached").value(true));
            }
            assertThat(fake.calls()).isEqualTo(2);
            // Cache hits don't use up the (here: 3 per hour) limit.
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}")).andExpect(status().isOk());
            }
        }

        @Test
        void specsWithANeed_andAsk_areNeverCached() throws Exception {
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\",\"need\":\"gaming\"}")).andExpect(jsonPath("$.cached").value(false));
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\",\"need\":\"gaming\"}")).andExpect(jsonPath("$.cached").value(false));
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"ASK\",\"question\":\"q\"}")).andExpect(jsonPath("$.cached").value(false));
            assertThat(fake.calls()).isEqualTo(3);
        }

        @Test
        void failures_areNotCached() throws Exception {
            fake.failWith(com.campus.marketplace.exception.AssistantUnavailableException.busy());
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}")).andExpect(jsonPath("$.unavailable").value(true));
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}"))
                    .andExpect(jsonPath("$.unavailable").value(false))
                    .andExpect(jsonPath("$.cached").value(false));
        }

        @Test
        void editingTheListing_evicts() throws Exception {
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}")).andExpect(status().isOk());
            mockMvc.perform(put("/api/listings/{id}", listing.getId()).header("Authorization", "Bearer " + sellerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Lamp\",\"description\":\"now with a new bulb\",\"price\":10.00,\"category\":\"ELECTRONICS\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}")).andExpect(jsonPath("$.cached").value(false));
            assertThat(fake.calls()).isEqualTo(2);
            assertThat(fake.lastPrompt().turns().get(0).text()).contains("now with a new bulb");
        }

        @Test
        void soldAndDeleted_evict() throws Exception {
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"VERDICT\"}")).andExpect(status().isOk());
            mockMvc.perform(patch("/api/listings/{id}/sold", listing.getId()).header("Authorization", "Bearer " + sellerToken))
                    .andExpect(status().isOk());
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"VERDICT\"}")).andExpect(jsonPath("$.cached").value(false));
            mockMvc.perform(delete("/api/listings/{id}", listing.getId()).header("Authorization", "Bearer " + sellerToken))
                    .andExpect(status().isNoContent());
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"VERDICT\"}")).andExpect(status().isNotFound());
            assertThat(fake.calls()).isEqualTo(2);
        }

        @Test
        void otherListings_keepTheirEntries() throws Exception {
            Listing other = AssistantTestSupport.listing(listings, seller, "Fan", "table fan", "12.00");
            mockMvc.perform(ask(other.getId(), token, "{\"mode\":\"SPECS\"}")).andExpect(status().isOk());
            mockMvc.perform(patch("/api/listings/{id}/sold", listing.getId()).header("Authorization", "Bearer " + sellerToken))
                    .andExpect(status().isOk());
            mockMvc.perform(ask(other.getId(), token, "{\"mode\":\"SPECS\"}")).andExpect(jsonPath("$.cached").value(true));
        }
    }
}
