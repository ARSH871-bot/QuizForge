package com.quizforge.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private String body(Map<String, Object> map) throws Exception {
        return json.writeValueAsString(map);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void registersLogsInAndIdentifiesTheCaller() throws Exception {
        String email = uniqueEmail();

        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email,
                                "password", "correct horse battery",
                                "displayName", "Ada"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(Matchers.startsWith("acc_")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        var login = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email,
                                "password", "correct horse battery"))))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("qf_session"))
                .andExpect(cookie().httpOnly("qf_session", true))
                .andReturn();

        var sessionCookie = login.getResponse().getCookie("qf_session");

        mvc.perform(get("/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void rejectsLoginWithAWrongPassword() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", email,
                        "password", "correct horse battery", "displayName", "Ada"))));

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", "wrong password here"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void rejectsRegistrationWithAShortPassword() throws Exception {
        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", uniqueEmail(),
                                "password", "short", "displayName", "Ada"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", email,
                        "password", "correct horse battery", "displayName", "Ada"))));

        var login = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email,
                                "password", "correct horse battery"))))
                .andReturn();
        var sessionCookie = login.getResponse().getCookie("qf_session");

        mvc.perform(post("/v1/auth/logout").cookie(sessionCookie))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void neverReturnsAPasswordResetTokenInTheResponse() throws Exception {
        // The prototype returned the reset token in the HTTP response, which
        // combined with an unauthenticated API allowed trivial account
        // takeover. This test exists to make that regression impossible.
        mvc.perform(post("/v1/auth/request-password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", uniqueEmail()))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.resetToken").doesNotExist());
    }
}
