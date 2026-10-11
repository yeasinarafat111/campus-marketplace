package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.exception.AssistantUnavailableException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.campus.marketplace.controller.AssistantTestSupport.ask;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The off switch, a missing key and the separate price-check switch. Each case runs with its own properties. */
class AssistantSwitchesTest {

    abstract static class Base {
        @Autowired MockMvc mockMvc;
        @Autowired UserRepository users;
        @Autowired ListingRepository listings;
        @Autowired JwtService jwt;
        Listing listing;
        String token;

        @BeforeEach
        void setUp() {
            listings.deleteAll();
            users.deleteAll();
            listing = AssistantTestSupport.listing(listings, AssistantTestSupport.seller(users), "Lamp", "desk lamp", "10.00");
            token = AssistantTestSupport.token(jwt, AssistantTestSupport.buyer(users));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.assistant.enabled=false")
    class WhenSwitchedOff extends Base {
        @Test
        void endpointIs503_andConfigHidesTheButton() throws Exception {
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value(AssistantUnavailableException.OFF));
            mockMvc.perform(get("/api/config"))
                    .andExpect(jsonPath("$.assistantEnabled").value(false))
                    .andExpect(jsonPath("$.assistantVerdictEnabled").value(false));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {"app.assistant.gateway=gemini", "app.assistant.api-key="})
    class WhenTheKeyIsMissing extends Base {
        @Test
        void endpointIs503_andConfigHidesTheButton() throws Exception {
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}"))
                    .andExpect(status().isServiceUnavailable());
            mockMvc.perform(get("/api/config")).andExpect(jsonPath("$.assistantEnabled").value(false));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.assistant.verdict-enabled=false")
    class WhenTheVerdictIsOff extends Base {
        @Test
        void verdictIs503_specsStillWork_andConfigHidesTheButton() throws Exception {
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"VERDICT\"}"))
                    .andExpect(status().isServiceUnavailable());
            mockMvc.perform(ask(listing.getId(), token, "{\"mode\":\"SPECS\"}")).andExpect(status().isOk());
            mockMvc.perform(get("/api/config"))
                    .andExpect(jsonPath("$.assistantEnabled").value(true))
                    .andExpect(jsonPath("$.assistantVerdictEnabled").value(false));
        }
    }
}
