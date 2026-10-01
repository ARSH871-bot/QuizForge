package com.quizforge.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in is exempt from rate limiting on the understanding that repeated
 * failures lock the account. That lockout never engaged until these tests
 * existed: the failure count was rolled back with the failed request.
 */
@AutoConfigureMockMvc
class LoginLockoutTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private static final String PASSWORD = "correct horse battery";

    private String register() throws Exception {
        String email = "lockout-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", "Ada"))))
                .andExpect(status().isCreated());
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    @Test
    void tenWrongPasswordsLockTheAccountEvenAgainstTheRightOne() throws Exception {
        String email = register();
        for (int i = 0; i < 10; i++) {
            login(email, "guess " + i).andExpect(status().isUnauthorized());
        }

        // Locked: the right password is refused too, and the answer is the same
        // as for a wrong one, so the lockout does not confirm the account exists.
        login(email, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void aSuccessfulSignInStartsTheCountAgain() throws Exception {
        String email = register();
        for (int i = 0; i < 9; i++) {
            login(email, "guess " + i);
        }
        login(email, PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 9; i++) {
            login(email, "guess again " + i);
        }
        login(email, PASSWORD).andExpect(status().isOk());
    }
}
