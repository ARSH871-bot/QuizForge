package com.quizforge.platform.web;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The web app's routes open without a login; the API behind them does not. */
@AutoConfigureMockMvc
class WebAppTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;

    @Test
    void appRoutesServeTheSinglePageWithoutALogin() throws Exception {
        for (String path : new String[]{"/", "/sign-in", "/app", "/app/new",
                "/app/t/trn_0190c2b4e7a87f4f9b1a2c3d4e5f6a7b", "/t/trn_0190c2b4e7a87f4f9b1a2c3d4e5f6a7b"}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    /**
     * MockMvc does not execute a forward, so the test above passes even if
     * nothing can serve the page it forwards to - which is exactly the state
     * this was in until a real container was run. Fetch the file itself.
     */
    @Test
    void thePageItForwardsToIsActuallyServed() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\">")));
    }

    @Test
    void anUnknownPathIsNotFoundRatherThanAServerError() throws Exception {
        mvc.perform(get("/assets/no-such-file.js"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theApiBehindThemStaysAuthenticated() throws Exception {
        mvc.perform(get("/v1/tournaments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/auth/me")).andExpect(status().isUnauthorized());
        // A route that is not the app's is not quietly turned into a page.
        mvc.perform(get("/app-secrets")).andExpect(status().isUnauthorized());
    }
}
