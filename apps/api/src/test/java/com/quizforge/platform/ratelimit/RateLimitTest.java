package com.quizforge.platform.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiting, and the budget it reports.
 *
 * <p>The headers matter as much as the refusal. A client told its budget only
 * once it has run out has been told too late — so the interesting assertions
 * here are about the successful responses.
 */
@AutoConfigureMockMvc
class RateLimitTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private RateLimiter limiter;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Caller(Cookie cookie, String workspaceId, UUID accountId) {
    }

    private Caller caller() throws Exception {
        String email = "rl-" + UUID.randomUUID() + "@example.test";

        String accountId = json.readTree(mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", "RL"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        Cookie cookie = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);

        String workspaceId = json.readTree(mvc.perform(post("/v1/workspaces")
                        .cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Acme " + UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        return new Caller(cookie, workspaceId, TypeId.parse("acc", accountId));
    }

    private MvcResult call(Caller c) throws Exception {
        return mvc.perform(get("/v1/question-banks")
                        .cookie(c.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, c.workspaceId()))
                .andReturn();
    }

    // -------------------------------------------------------------- headers

    @Test
    void everySuccessfulResponseReportsTheBudget() throws Exception {
        // The point of the whole feature: a client can slow down before it is
        // refused, rather than discovering the limit by hitting it.
        var c = caller();

        var result = call(c);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(RateLimitFilter.LIMIT_HEADER))
                .as("the allowance")
                .isNotNull();
        assertThat(result.getResponse().getHeader(RateLimitFilter.REMAINING_HEADER))
                .as("what is left of it")
                .isNotNull();
        assertThat(result.getResponse().getHeader(RateLimitFilter.RESET_HEADER))
                .as("when more arrives")
                .isNotNull();
    }

    @Test
    void remainingDecreasesAsTheBudgetIsSpent() throws Exception {
        var c = caller();

        int first = Integer.parseInt(call(c).getResponse().getHeader(RateLimitFilter.REMAINING_HEADER));
        int second = Integer.parseInt(call(c).getResponse().getHeader(RateLimitFilter.REMAINING_HEADER));

        assertThat(second)
                .as("each request costs a token")
                .isLessThan(first);
    }

    @Test
    void resetIsZeroWhileRequestsRemain() throws Exception {
        var c = caller();

        assertThat(call(c).getResponse().getHeader(RateLimitFilter.RESET_HEADER))
                .as("nothing to wait for when the budget is not exhausted")
                .isEqualTo("0");
    }

    // -------------------------------------------------------------- refusal

    @Test
    void exhaustingTheBudgetIsRefusedWithSomethingToActOn() throws Exception {
        var c = caller();
        call(c);                                  // create the bucket
        limiter.drain(c.accountId());             // then empty it

        var refused = call(c);

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getContentAsString()).contains("RATE_LIMITED");
        assertThat(refused.getResponse().getHeader(RateLimitFilter.REMAINING_HEADER))
                .isEqualTo("0");

        String retryAfter = refused.getResponse().getHeader(RateLimitFilter.RETRY_AFTER_HEADER);
        assertThat(retryAfter)
                .as("a 429 nobody can act on is just a rejection")
                .isNotNull();
        assertThat(Integer.parseInt(retryAfter))
                .as("never zero, or a client retries immediately and is refused again")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aRefusedResponseStillReportsTheBudget() throws Exception {
        var c = caller();
        call(c);
        limiter.drain(c.accountId());

        var refused = call(c);

        assertThat(refused.getResponse().getHeader(RateLimitFilter.LIMIT_HEADER)).isNotNull();
        assertThat(refused.getResponse().getHeader(RateLimitFilter.RESET_HEADER)).isNotNull();
    }

    @Test
    void theBudgetRefillsOverTime() throws Exception {
        // Continuous refill, not a window that resets on a boundary. Ageing the
        // bucket is the same trick the expiry sweep test uses for attempts.
        var c = caller();
        call(c);
        limiter.drain(c.accountId());

        assertThat(call(c).getResponse().getStatus())
                .as("empty means refused")
                .isEqualTo(429);

        jdbc.update("UPDATE rate_limit_bucket SET refilled_at = now() - interval '1 minute' "
                + "WHERE subject_id = ?", c.accountId());

        assertThat(call(c).getResponse().getStatus())
                .as("a minute later the bucket has refilled")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------- scoping

    @Test
    void oneCallersBudgetIsNotAnothers() throws Exception {
        var mine = caller();
        var theirs = caller();

        call(mine);
        limiter.drain(mine.accountId());

        assertThat(call(mine).getResponse().getStatus()).isEqualTo(429);
        assertThat(call(theirs).getResponse().getStatus())
                .as("a runaway caller must not starve everybody else")
                .isEqualTo(200);
    }

    @Test
    void aWorkspaceCanRaiseItsOwnLimit() throws Exception {
        // A column rather than a config key, so M7's billing tiers change a row
        // instead of shipping a deploy.
        var c = caller();
        UUID workspaceId = TypeId.parse("wsp", c.workspaceId());

        int before = limiter.limitFor(workspaceId);
        jdbc.update("UPDATE workspace SET rate_limit_per_minute = ? WHERE id = ?",
                before + 500, workspaceId);

        assertThat(limiter.limitFor(workspaceId)).isEqualTo(before + 500);

        assertThat(call(c).getResponse().getHeader(RateLimitFilter.LIMIT_HEADER))
                .as("the header reports the workspace's limit, not the default")
                .isEqualTo(String.valueOf(before + 500));
    }

    @Test
    void unauthenticatedRequestsAreNotCharged() throws Exception {
        // /v1/auth/** has no workspace to scope a bucket to. Login has its own
        // control - repeated failures lock the account - which is a better
        // answer to password guessing than a shared counter.
        var result = mvc.perform(get("/v1/auth/health")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(RateLimitFilter.LIMIT_HEADER))
                .as("nothing to report when nothing is being limited")
                .isNull();
    }

    // ---------------------------------------------------------- the limiter

    @Test
    void aBurstUpToTheWholeAllowanceIsPermitted() throws Exception {
        // A bucket, not a window: a caller may spend its allowance at once and
        // then waits. A window would let it spend the allowance twice across a
        // boundary, which is double the rate at the worst moment.
        var c = caller();
        UUID workspaceId = TypeId.parse("wsp", c.workspaceId());
        jdbc.update("UPDATE workspace SET rate_limit_per_minute = 5 WHERE id = ?", workspaceId);

        TenantContext.set(workspaceId);
        try {
            UUID subject = UUID.randomUUID();
            for (int i = 1; i <= 5; i++) {
                assertThat(limiter.consume(subject, workspaceId).allowed())
                        .as("request %s of the allowance", i)
                        .isTrue();
            }
            assertThat(limiter.consume(subject, workspaceId).allowed())
                    .as("the sixth exceeds it")
                    .isFalse();
        } finally {
            TenantContext.clear();
        }
    }
}
