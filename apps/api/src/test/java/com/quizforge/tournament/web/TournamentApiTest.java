package com.quizforge.tournament.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scheduling, amending and deleting tournaments, over HTTP.
 *
 * <p>Every rule here is expressed against state derived from the clock, so the
 * tests move the window rather than setting a flag — there is no flag to set.
 */
@AutoConfigureMockMvc
class TournamentApiTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Organiser(Cookie cookie, String workspaceId, String bankId) {
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** An account with a workspace and a bank holding four questions. */
    private Organiser organiser() throws Exception {
        String email = "org-" + UUID.randomUUID() + "@example.test";

        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", "Organiser"))))
                .andExpect(status().isCreated());

        Cookie cookie = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);

        String workspaceId = body(mvc.perform(post("/v1/workspaces")
                        .cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Acme " + UUID.randomUUID()))))
                .andExpect(status().isCreated())).get("id").asText();

        String bankId = body(mvc.perform(post("/v1/question-banks")
                        .cookie(cookie)
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Geo " + UUID.randomUUID()))))
                .andExpect(status().isCreated())).get("id").asText();

        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/v1/question-banks/" + bankId + "/questions")
                            .cookie(cookie)
                            .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "type", "SINGLE_CHOICE",
                                    "prompt", "Question " + i + "?",
                                    "payload", Map.of("kind", "choice", "options", List.of(
                                            Map.of("text", "alpha", "correct", true),
                                            Map.of("text", "beta", "correct", false)))))))
                    .andExpect(status().isCreated());
        }

        return new Organiser(cookie, workspaceId, bankId);
    }

    /** A draft whose window is relative to now, so state is controllable. */
    private Map<String, Object> draft(Organiser o, Duration opensIn, Duration closesIn) {
        Map<String, Object> draft = new HashMap<>();
        draft.put("name", "Weekly " + UUID.randomUUID());
        draft.put("bankId", o.bankId());
        draft.put("opensAt", Instant.now().plus(opensIn).toString());
        draft.put("closesAt", Instant.now().plus(closesIn).toString());
        draft.put("questions", 2);
        draft.put("timeLimitSeconds", 600);
        draft.put("maxAttempts", 1);
        draft.put("scoringPolicy", "BEST");
        return draft;
    }

    private JsonNode create(Organiser o, Map<String, Object> draft) throws Exception {
        return body(mvc.perform(post("/v1/tournaments")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(draft)))
                .andExpect(status().isCreated()));
    }

    // ---------------------------------------------------------------- create

    @Test
    void aTournamentCanBeScheduled() throws Exception {
        var o = organiser();

        var created = create(o, draft(o, Duration.ofHours(1), Duration.ofDays(1)));

        assertThat(created.get("id").asText()).matches("^trn_[0-9a-f]{32}$");
        assertThat(created.get("state").asText()).isEqualTo("SCHEDULED");
        assertThat(created.get("bankId").asText()).isEqualTo(o.bankId());
        assertThat(created.get("scoringPolicy").asText()).isEqualTo("BEST");
    }

    @Test
    void stateIsDerivedFromTheClockRatherThanStored() throws Exception {
        // Nothing writes state. The same row answers differently as the window
        // moves around it, which is why every rule below is expressed this way.
        var o = organiser();

        var scheduled = create(o, draft(o, Duration.ofHours(1), Duration.ofDays(1)));
        assertThat(scheduled.get("state").asText()).isEqualTo("SCHEDULED");

        var open = create(o, draft(o, Duration.ofMinutes(-1), Duration.ofDays(1)));
        assertThat(open.get("state").asText()).isEqualTo("OPEN");

        var closed = create(o, draft(o, Duration.ofDays(-2), Duration.ofDays(-1)));
        assertThat(closed.get("state").asText()).isEqualTo("CLOSED");
    }

    @Test
    void aTournamentAskingForMoreQuestionsThanTheBankHoldsIsRefused() throws Exception {
        // Refused at creation, not discovered by the first player. The bank
        // holds four; ask for ten.
        var o = organiser();
        var draft = draft(o, Duration.ofHours(1), Duration.ofDays(1));
        draft.put("questions", 10);

        mvc.perform(post("/v1/tournaments")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(draft)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("only 4")));
    }

    @Test
    void aWindowThatClosesBeforeItOpensIsRefused() throws Exception {
        var o = organiser();
        var draft = draft(o, Duration.ofDays(2), Duration.ofDays(1));

        mvc.perform(post("/v1/tournaments")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(draft)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aBankInAnotherWorkspaceIsNotFound() throws Exception {
        // Not "forbidden": indistinguishable from a bank that does not exist.
        var mine = organiser();
        var stranger = organiser();

        var draft = draft(mine, Duration.ofHours(1), Duration.ofDays(1));
        draft.put("bankId", stranger.bankId());

        mvc.perform(post("/v1/tournaments")
                        .cookie(mine.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, mine.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(draft)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- amend

    @Test
    void aScheduledTournamentCanBeAmended() throws Exception {
        var o = organiser();
        String id = create(o, draft(o, Duration.ofHours(1), Duration.ofDays(1)))
                .get("id").asText();

        var amended = draft(o, Duration.ofHours(2), Duration.ofDays(3));
        amended.put("name", "Renamed");
        amended.put("questions", 3);
        amended.put("scoringPolicy", "AVERAGE");

        mvc.perform(patch("/v1/tournaments/" + id)
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(amended)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.questions").value(3))
                .andExpect(jsonPath("$.scoringPolicy").value("AVERAGE"));
    }

    @Test
    void anOpenTournamentCannotBeAmended() throws Exception {
        // Players have started under the current rules. Changing them now would
        // mean two players played different games and were ranked together.
        var o = organiser();
        String id = create(o, draft(o, Duration.ofMinutes(-1), Duration.ofDays(1)))
                .get("id").asText();

        mvc.perform(patch("/v1/tournaments/" + id)
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                draft(o, Duration.ofHours(1), Duration.ofDays(2)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("open")));
    }

    @Test
    void aClosedTournamentCannotBeAmended() throws Exception {
        var o = organiser();
        String id = create(o, draft(o, Duration.ofDays(-2), Duration.ofDays(-1)))
                .get("id").asText();

        mvc.perform(patch("/v1/tournaments/" + id)
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                draft(o, Duration.ofHours(1), Duration.ofDays(2)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("closed")));
    }

    // --------------------------------------------------------------- delete

    @Test
    void aScheduledTournamentWithNoAttemptsCanBeDeleted() throws Exception {
        // A plan nobody has played. Deleting it loses nothing, which is why
        // this is a real delete rather than another tombstone.
        var o = organiser();
        String id = create(o, draft(o, Duration.ofHours(1), Duration.ofDays(1)))
                .get("id").asText();

        mvc.perform(delete("/v1/tournaments/" + id)
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/tournaments/" + id)
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anOpenTournamentCannotBeDeleted() throws Exception {
        var o = organiser();
        String id = create(o, draft(o, Duration.ofMinutes(-1), Duration.ofDays(1)))
                .get("id").asText();

        mvc.perform(delete("/v1/tournaments/" + id)
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aPlayedTournamentIsRefusedByTheOpenRuleBeforeTheAttemptRuleIsReached() throws Exception {
        // Named for what it actually proves. A tournament with attempts is
        // always OPEN or CLOSED - attempts can only start while it is open, and
        // an open tournament can no longer be amended back to scheduled - so the
        // open rule refuses this first and the attempt rule is never consulted.
        var o = organiser();
        String id = create(o, draft(o, Duration.ofMinutes(-1), Duration.ofDays(1)))
                .get("id").asText();

        mvc.perform(post("/v1/tournaments/" + id + "/attempts")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf()))
                .andExpect(status().isCreated());

        mvc.perform(delete("/v1/tournaments/" + id)
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("open")));
    }

    @Test
    void theAttemptGuardStillRefusesADeleteIfTheWindowIsMovedUnderIt() throws Exception {
        // The attempt guard is unreachable through the API today: nothing can
        // put a played tournament back into SCHEDULED. It is defence in depth,
        // and defence in depth that has never been executed is an assumption.
        //
        // So the window is moved directly in the database - the one state the
        // guard exists to survive, and the state any future admin tool or data
        // fix could produce. This is also what proves the TournamentUsage port
        // is wired: the count crosses from `play` back to `tournament`, which
        // cannot depend on it.
        var o = organiser();
        String id = create(o, draft(o, Duration.ofMinutes(-1), Duration.ofDays(1)))
                .get("id").asText();

        mvc.perform(post("/v1/tournaments/" + id + "/attempts")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf()))
                .andExpect(status().isCreated());

        jdbc.update("UPDATE tournament SET opens_at = now() + interval '1 day', "
                        + "closes_at = now() + interval '2 days' WHERE id = ?",
                com.quizforge.platform.id.TypeId.parse("trn", id));

        mvc.perform(delete("/v1/tournaments/" + id)
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("attempt")));
    }

    // ------------------------------------------------------ organiser's view

    @Test
    void theOrganiserCanSeeWhoHasPlayed() throws Exception {
        var o = organiser();
        String id = create(o, draft(o, Duration.ofMinutes(-1), Duration.ofDays(1)))
                .get("id").asText();

        String attemptId = body(mvc.perform(post("/v1/tournaments/" + id + "/attempts")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                        .with(csrf()))
                .andExpect(status().isCreated())).get("id").asText();

        mvc.perform(get("/v1/tournaments/" + id + "/attempts-summary")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(attemptId))
                .andExpect(jsonPath("$.data[0].state").value("STARTED"))
                .andExpect(jsonPath("$.data[0].accountId")
                        .value(matchesPattern("^acc_[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.data[0].outOf").value(2));
    }

    @Test
    void theOrganisersViewShowsAttemptsThatNoLeaderboardWould() throws Exception {
        // An attempt in progress ranks nowhere, but the organiser still needs to
        // know it exists. This is what makes the view distinct from standings.
        var o = organiser();
        String id = create(o, draft(o, Duration.ofMinutes(-1), Duration.ofDays(1)))
                .get("id").asText();

        mvc.perform(post("/v1/tournaments/" + id + "/attempts")
                .cookie(o.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId())
                .with(csrf()));

        mvc.perform(get("/v1/tournaments/" + id + "/standings")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId()))
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/v1/tournaments/" + id + "/attempts-summary")
                        .cookie(o.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, o.workspaceId()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ------------------------------------------------------- authorization

    @Test
    void anotherWorkspacesTournamentIsNotFound() throws Exception {
        var mine = organiser();
        var stranger = organiser();
        String theirs = create(stranger, draft(stranger, Duration.ofHours(1), Duration.ofDays(1)))
                .get("id").asText();

        mvc.perform(get("/v1/tournaments/" + theirs)
                        .cookie(mine.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, mine.workspaceId()))
                .andExpect(status().isNotFound());
    }
}
