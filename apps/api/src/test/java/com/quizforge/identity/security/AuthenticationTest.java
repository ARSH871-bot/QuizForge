package com.quizforge.identity.security;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthenticationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;

    @Test
    void rejectsUnauthenticatedAccessToProtectedEndpoints() throws Exception {
        mvc.perform(get("/v1/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void allowsUnauthenticatedAccessToTheAuthPath() throws Exception {
        mvc.perform(get("/v1/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void rejectsAMalformedApiKey() throws Exception {
        mvc.perform(get("/v1/workspaces").header("Authorization", "Bearer qf_live_nonsense"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theLegacyApiServesNothing() throws Exception {
        // This was the last unauthenticated surface in the product. It is
        // asserted positively rather than assumed gone.
        //
        // The status is 401 rather than 404 because Spring Security intercepts
        // an unknown path before the dispatcher can report it missing. That is
        // the better behaviour - an anonymous caller learns nothing about which
        // routes exist - so the assertion is that it serves no data, not that
        // it 404s.
        mvc.perform(get("/api/questions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/quizzes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }
}
