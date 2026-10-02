package com.quizforge.play.web;

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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-question results for an organiser.
 *
 * <p>The numbers are checked, but most of the weight is on what the endpoint
 * refuses: a player, who must not learn how others answered; another
 * workspace, which must not learn the tournament exists; and an attempt still
 * in progress, whose unanswered questions would read as skipped.
 */
@AutoConfigureMockMvc
class InsightApiTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Setup(Cookie organiser, String workspaceId, String tournamentId) {
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private Cookie account(String displayName) throws Exception {
        String email = "insight-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", displayName))))
                .andExpect(status().isCreated());
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);
    }

    /** An open tournament drawing both questions of a two-question bank. */
    private Setup openTournament() throws Exception {
        Cookie organiser = account("Organiser");
        String workspaceId = body(mvc.perform(post("/v1/workspaces").cookie(organiser).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Quiz Club " + UUID.randomUUID())))))
                .get("id").asText();
        String bankId = body(mvc.perform(post("/v1/question-banks").cookie(organiser).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Geo " + UUID.randomUUID())))))
                .get("id").asText();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/v1/question-banks/" + bankId + "/questions").cookie(organiser).with(csrf())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "type", "SINGLE_CHOICE", "prompt", "Question " + i + "?",
                                    "payload", Map.of("kind", "choice", "options", List.of(
                                            Map.of("text", "right", "correct", true),
                                            Map.of("text", "wrong", "correct", false)))))))
                    .andExpect(status().isCreated());
        }
        String tournamentId = body(mvc.perform(post("/v1/tournaments").cookie(organiser).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "name", "Friday Quiz", "bankId", bankId,
                        "opensAt", Instant.now().minus(1, ChronoUnit.MINUTES).toString(),
                        "closesAt", Instant.now().plus(1, ChronoUnit.DAYS).toString(),
                        "questions", 2, "maxAttempts", 1)))))
                .get("id").asText();
        return new Setup(organiser, workspaceId, tournamentId);
    }

    private Cookie joinedPlayer(Setup s, String name) throws Exception {
        Cookie player = account(name);
        mvc.perform(post("/v1/join/" + s.tournamentId()).cookie(player).with(csrf()))
                .andExpect(status().isOk());
        return player;
    }

    /**
     * Plays one attempt. {@code answers.get(i)} is the answer at position
     * {@code i + 1}, or null to leave that position unanswered.
     */
    private void play(Setup s, Cookie player, List<String> answers, boolean submit) throws Exception {
        String attemptId = body(mvc.perform(post("/v1/tournaments/" + s.tournamentId() + "/attempts")
                .cookie(player).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))).get("id").asText();
        for (int i = 0; i < answers.size(); i++) {
            if (answers.get(i) == null) {
                continue;
            }
            mvc.perform(post("/v1/attempts/" + attemptId + "/questions/" + (i + 1) + "/answer")
                            .cookie(player).with(csrf())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("answer", answers.get(i)))))
                    .andExpect(status().isOk());
        }
        if (submit) {
            mvc.perform(post("/v1/attempts/" + attemptId + "/submit").cookie(player).with(csrf())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))
                    .andExpect(status().isOk());
        }
    }

    private ResultActions stats(Setup s, Cookie caller, String workspaceHeader) throws Exception {
        var request = get("/v1/tournaments/" + s.tournamentId() + "/question-stats").cookie(caller);
        if (workspaceHeader != null) {
            request = request.header(SessionAuthFilter.WORKSPACE_HEADER, workspaceHeader);
        }
        return mvc.perform(request);
    }

    @Test
    void eachQuestionReportsShownAnsweredAndCorrectHardestFirst() throws Exception {
        // Each attempt shuffles the order, so which question sits at which
        // position is random. The scenario is built so the totals come out
        // the same either way: whichever question Grace saw first is the one
        // answered twice and got right once; the other was answered once,
        // correctly.
        var s = openTournament();
        play(s, joinedPlayer(s, "Ada"), List.of("right", "right"), true);
        play(s, joinedPlayer(s, "Grace"), Arrays.asList("wrong", null), true);

        JsonNode data = body(stats(s, s.organiser(), s.workspaceId()).andExpect(status().isOk()))
                .get("data");

        assertThat(data).hasSize(2);
        JsonNode hardest = data.get(0);
        assertThat(hardest.get("shown").asInt()).isEqualTo(2);
        assertThat(hardest.get("answered").asInt()).isEqualTo(2);
        assertThat(hardest.get("correct").asInt()).isEqualTo(1);
        assertThat(hardest.get("correctRate").asDouble()).isEqualTo(0.5);

        JsonNode easiest = data.get(1);
        assertThat(easiest.get("shown").asInt()).isEqualTo(2);
        assertThat(easiest.get("answered").asInt()).isEqualTo(1);
        assertThat(easiest.get("correct").asInt()).isEqualTo(1);
        assertThat(easiest.get("correctRate").asDouble()).isEqualTo(1.0);

        for (JsonNode row : data) {
            assertThat(row.get("questionId").asText()).matches("^qst_[0-9a-f]{32}$");
            assertThat(row.get("type").asText()).isEqualTo("SINGLE_CHOICE");
            assertThat(row.get("prompt").asText()).matches("^Question [01]\\?$");
        }
    }

    @Test
    void anAttemptStillInProgressIsNotCounted() throws Exception {
        // Counted, it would show its unanswered question as skipped and its
        // answered one as a result before the player had finished.
        var s = openTournament();
        play(s, joinedPlayer(s, "Ada"), List.of("right", "right"), true);
        play(s, joinedPlayer(s, "Linus"), List.of("wrong"), false);

        JsonNode data = body(stats(s, s.organiser(), s.workspaceId()).andExpect(status().isOk()))
                .get("data");

        for (JsonNode row : data) {
            assertThat(row.get("shown").asInt()).isEqualTo(1);
            assertThat(row.get("correct").asInt()).isEqualTo(1);
        }
    }

    @Test
    void aTournamentNobodyHasFinishedHasNoRows() throws Exception {
        var s = openTournament();

        stats(s, s.organiser(), s.workspaceId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void theReportNeverCarriesTheAnswers() throws Exception {
        var s = openTournament();
        play(s, joinedPlayer(s, "Ada"), List.of("right", "wrong"), true);

        String response = stats(s, s.organiser(), s.workspaceId())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The option texts are the answers; neither appears anywhere.
        assertThat(response).doesNotContain("right").doesNotContain("wrong")
                .doesNotContain("payload").doesNotContain("options");
    }

    @Test
    void aPlayerCannotSeeHowOthersAnswered() throws Exception {
        var s = openTournament();
        Cookie ada = joinedPlayer(s, "Ada");
        play(s, ada, List.of("right", "right"), true);

        stats(s, ada, s.workspaceId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void anotherWorkspaceIsToldTheTournamentDoesNotExist() throws Exception {
        // With the stranger's own workspace in scope, RLS hides the tournament,
        // so the answer is 404 and not an empty report that confirms it exists.
        var owner = openTournament();
        play(owner, joinedPlayer(owner, "Ada"), List.of("right", "right"), true);
        var stranger = openTournament();

        mvc.perform(get("/v1/tournaments/" + owner.tournamentId() + "/question-stats")
                        .cookie(stranger.organiser())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, stranger.workspaceId()))
                .andExpect(status().isNotFound());

        // And naming the owner's workspace without belonging to it is refused outright.
        mvc.perform(get("/v1/tournaments/" + owner.tournamentId() + "/question-stats")
                        .cookie(stranger.organiser())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, owner.workspaceId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void omittingTheWorkspaceIsRefusedRatherThanReadingWithoutTenancy() throws Exception {
        // With no tenant in scope the connection runs without RLS, so this
        // must stop before the query rather than rely on the policy.
        var s = openTournament();
        play(s, joinedPlayer(s, "Ada"), List.of("right", "right"), true);

        stats(s, s.organiser(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void anAnonymousCallerIsAskedToAuthenticate() throws Exception {
        var s = openTournament();

        mvc.perform(get("/v1/tournaments/" + s.tournamentId() + "/question-stats")
                        .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))
                .andExpect(status().isUnauthorized());
    }
}
