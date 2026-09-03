package com.quizforge.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "quizforge.security.session-cookie-secure=false")
class AuthControllerCookieSecurePropertyTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private static String sessionSetCookie(org.springframework.test.web.servlet.MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("qf_session="))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void sessionCookieSecureFlagCanBeDisabledForStrictLocalHttpClients() throws Exception {
        String email = "secure-cookie-" + UUID.randomUUID() + "@example.test";

        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email,
                                "password", "correct horse battery",
                                "displayName", "Ada"))))
                .andExpect(status().isCreated());

        var login = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email,
                                "password", "correct horse battery"))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(sessionSetCookie(login))
                .as("strict local HTTP clients such as .NET CookieContainer can opt out explicitly")
                .doesNotContain("; Secure");
    }
}
