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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Share links: a stranger can see a tournament, join it, play it — and nothing more. */
@AutoConfigureMockMvc
class JoinApiTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Setup(Cookie organiser, String workspaceId, String bankId, String tournamentId) {
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private Cookie account(String displayName) throws Exception {
        String email = "join-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", displayName))))
                .andExpect(status().isCreated());
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);
    }

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
        return new Setup(organiser, workspaceId, bankId, tournamentId);
    }

    @Test
    void aShareLinkShowsThePublicCardWithoutAnyCredential() throws Exception {
        var s = openTournament();

        mvc.perform(get("/v1/join/" + s.tournamentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Friday Quiz"))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.questions").value(2))
                .andExpect(jsonPath("$.organiser").isNotEmpty())
                // Nothing a player could use to prepare.
                .andExpect(jsonPath("$.bankId").doesNotExist());

        mvc.perform(get("/v1/join/trn_00000000000000000000000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void joiningNeedsAnAccountAndIsIdempotent() throws Exception {
        var s = openTournament();

        mvc.perform(post("/v1/join/" + s.tournamentId()).with(csrf()))
                .andExpect(status().isUnauthorized());

        Cookie player = account("Ada");
        mvc.perform(post("/v1/join/" + s.tournamentId()).cookie(player).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joined").value(true))
                .andExpect(jsonPath("$.workspace.id").value(s.workspaceId()))
                .andExpect(jsonPath("$.workspace.role").value("PLAYER"));

        mvc.perform(post("/v1/join/" + s.tournamentId()).cookie(player).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joined").value(false));
    }

    @Test
    void anOrganiserOpeningTheirOwnLinkStaysOwner() throws Exception {
        var s = openTournament();

        mvc.perform(post("/v1/join/" + s.tournamentId()).cookie(s.organiser()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joined").value(false))
                .andExpect(jsonPath("$.workspace.role").value("OWNER"));
    }

    @Test
    void aPlayerCanPlayButCannotReadTheAnswersOrTheOtherPlayers() throws Exception {
        var s = openTournament();
        Cookie player = account("Ada");
        mvc.perform(post("/v1/join/" + s.tournamentId()).cookie(player).with(csrf()))
                .andExpect(status().isOk());

        // Plays.
        mvc.perform(post("/v1/tournaments/" + s.tournamentId() + "/attempts").cookie(player).with(csrf())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))
                .andExpect(status().isCreated());

        // Refused everything that would reveal answers or other players.
        for (String path : List.of(
                "/v1/question-banks",
                "/v1/question-banks/" + s.bankId(),
                "/v1/question-banks/" + s.bankId() + "/questions",
                "/v1/members",
                "/v1/tournaments/" + s.tournamentId() + "/attempts-summary")) {
            mvc.perform(get(path).cookie(player)
                            .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        // And the organiser still can.
        mvc.perform(get("/v1/question-banks/" + s.bankId() + "/questions").cookie(s.organiser())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))
                .andExpect(status().isOk());
    }

    @Test
    void theLeaderboardNamesItsPlayers() throws Exception {
        var s = openTournament();
        Cookie player = account("Ada Lovelace");
        mvc.perform(post("/v1/join/" + s.tournamentId()).cookie(player).with(csrf()));

        String attemptId = body(mvc.perform(post("/v1/tournaments/" + s.tournamentId() + "/attempts")
                .cookie(player).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))).get("id").asText();
        for (int position = 1; position <= 2; position++) {
            mvc.perform(post("/v1/attempts/" + attemptId + "/questions/" + position + "/answer")
                    .cookie(player).with(csrf())
                    .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("answer", "right"))));
        }
        mvc.perform(post("/v1/attempts/" + attemptId + "/submit").cookie(player).with(csrf())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))
                .andExpect(status().isOk());

        JsonNode standings = body(mvc.perform(get("/v1/tournaments/" + s.tournamentId() + "/standings")
                .cookie(player).header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId())));
        assertThat(standings.get("data").get(0).get("displayName").asText()).isEqualTo("Ada Lovelace");
        assertThat(standings.get("data").get(0).get("score").asDouble()).isEqualTo(2.0);
    }
}
