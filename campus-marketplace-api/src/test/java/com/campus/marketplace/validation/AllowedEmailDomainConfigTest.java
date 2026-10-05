package com.campus.marketplace.validation;

import com.campus.marketplace.service.MailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The allowed domain comes from configuration: changing the property changes both the rule and the message. */
@SpringBootTest(properties = "app.registration.allowed-email-domain=@Example.EDU")
@AutoConfigureMockMvc
class AllowedEmailDomainConfigTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MailService mailService;

    @Test
    void configuredDomainIsAccepted() throws Exception {
        signup("someone@example.edu").andExpect(status().isCreated());
    }

    @Test
    void defaultDomainIsRejectedOnceChanged_andMessageNamesConfiguredDomain() throws Exception {
        signup("someone@seu.edu.bd")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").value("Only @example.edu email addresses can register"));
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Someone","email":"%s","phone":"01712345678","password":"correct-horse"}""".formatted(email)));
    }
}
