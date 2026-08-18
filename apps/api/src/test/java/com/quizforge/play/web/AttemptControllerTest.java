package com.quizforge.play.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.tenancy.TenantContext;
import com.quizforge.tournament.ScoringPolicy;
import com.quizforge.tournament.app.TournamentDraft;
import com.quizforge.tournament.app.TournamentService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AttemptControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TournamentService tournaments;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    private static final String RIGHT = "alpha";
    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Player(Cookie session, String workspaceHeader, String tournamentId) {
    }

    /** Registers, logs in, and seeds a playable tournament owned by that account. */
    private Player player() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.test";
        UUID accountId = accounts.register(email, PASSWORD, "Test User").getId();

        var workspace = workspaces.create(accountId, "Acme " + UUID.randomUUID());
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), accountId, "Geography");

        for (int i = 0; i < 4; i++) {
            questions.author(bank.getId(), accountId, QuestionType.SINGLE_CHOICE,
                    "Question " + i + "?",
                    new ChoicePayload(List.of(
                            new ChoicePayload.Option(RIGHT, true),
                            new ChoicePayload.Option("beta", false))),
                    "EASY");
        }

        Instant opens = Instant.now().minus(Duration.ofMinutes(1));
        var tournament = tournaments.create(workspace.getId(), accountId,
                new TournamentDraft("Quiz", bank.getId(), opens,
                        opens.plus(Duration.ofDays(1)), 2, 600, 1, ScoringPolicy.BEST));
        TenantContext.clear();

        var login = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        return new Player(login.getResponse().getCookie(SessionAuthFilter.COOKIE_NAME),
                TypeId.render("wsp", workspace.getId()),
                TypeId.render("trn", tournament.getId()));
    }

    @Test
    void aPlayerCanPlayATournamentEndToEnd() throws Exception {
        var p = player();

        var started = mvc.perform(post("/v1/tournaments/" + p.tournamentId() + "/attempts")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questions").value(2))
                .andReturn();

        String attemptId = json.readTree(started.getResponse().getContentAsString())
                .get("id").asText();
        assertThat(attemptId).startsWith("att_");

        for (int position = 1; position <= 2; position++) {
            mvc.perform(get("/v1/attempts/" + attemptId + "/questions/" + position)
                            .cookie(p.session())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.prompt").isNotEmpty())
                    .andExpect(jsonPath("$.options").isArray());

            mvc.perform(post("/v1/attempts/" + attemptId + "/questions/" + position + "/answer")
                            .cookie(p.session())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("answer", RIGHT))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.correct").value(true));
        }

        mvc.perform(post("/v1/attempts/" + attemptId + "/submit")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(2))
                .andExpect(jsonPath("$.outOf").value(2))
                .andExpect(jsonPath("$.percentage").value(100.0));
    }

    @Test
    void aQuestionResponseNeverNamesTheCorrectAnswer() throws Exception {
        // Asserted at the HTTP boundary as well as the service, because a
        // serialiser change could reintroduce the leak without touching either.
        var p = player();

        var started = mvc.perform(post("/v1/tournaments/" + p.tournamentId() + "/attempts")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andReturn();
        String attemptId = json.readTree(started.getResponse().getContentAsString())
                .get("id").asText();

        String body = mvc.perform(get("/v1/attempts/" + attemptId + "/questions/1")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("correct");
    }

    @Test
    void standingsAppearAfterSubmission() throws Exception {
        var p = player();

        var started = mvc.perform(post("/v1/tournaments/" + p.tournamentId() + "/attempts")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andReturn();
        String attemptId = json.readTree(started.getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(post("/v1/attempts/" + attemptId + "/questions/1/answer")
                .cookie(p.session())
                .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("answer", RIGHT))));

        mvc.perform(post("/v1/attempts/" + attemptId + "/submit")
                .cookie(p.session())
                .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()));

        mvc.perform(get("/v1/tournaments/" + p.tournamentId() + "/standings")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].score").value(1.0));
    }

    @Test
    void anUnauthenticatedWriteIsRejectedByCsrfBeforeAuthentication() throws Exception {
        // 403, not 401: the CSRF filter runs ahead of the authentication entry
        // point, so a cookie-auth write without a token never reaches it. That
        // ordering is Spring's and is correct - the request is refused either
        // way - but it is worth asserting so the behaviour is not mistaken for
        // a bug later.
        var p = player();

        mvc.perform(post("/v1/tournaments/" + p.tournamentId() + "/attempts"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedReadReturnsProblemDetails() throws Exception {
        // Reads are not CSRF-protected, so this reaches the entry point and
        // gets the documented 401 shape.
        var p = player();

        mvc.perform(get("/v1/tournaments/" + p.tournamentId() + "/standings"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void anIdentifierOfTheWrongTypeIsABadRequestNotAConfusingNotFound() throws Exception {
        var p = player();

        // A tournament id where an attempt id belongs.
        mvc.perform(post("/v1/attempts/" + p.tournamentId() + "/submit")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void anotherAccountCannotReadSomeoneElsesAttempt() throws Exception {
        var owner = player();
        var stranger = player();

        var started = mvc.perform(post("/v1/tournaments/" + owner.tournamentId() + "/attempts")
                        .cookie(owner.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, owner.workspaceHeader())
                        .with(csrf()))
                .andReturn();
        String attemptId = json.readTree(started.getResponse().getContentAsString())
                .get("id").asText();

        // Not found rather than forbidden - existence is not disclosed.
        mvc.perform(get("/v1/attempts/" + attemptId + "/questions/1")
                        .cookie(stranger.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, stranger.workspaceHeader())
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aStrangerCannotStartAnAttemptOnAnotherWorkspacesTournament() throws Exception {
        // Probe for the same defect found in the standings endpoint: with no
        // workspace header there is no tenant, so RLS does not engage and
        // `requireOpen` resolves a tournament the caller has no claim to.
        var owner = player();
        var stranger = player();

        mvc.perform(post("/v1/tournaments/" + owner.tournamentId() + "/attempts")
                        .cookie(stranger.session())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theAttemptIdentifierRoundTripsBetweenStartAndResult() throws Exception {
        // The defect this closes: start returned `att_…` while result returned a
        // bare UUID, so a client could not use the value it had just been given.
        var p = player();

        var started = mvc.perform(post("/v1/tournaments/" + p.tournamentId() + "/attempts")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        String attemptId = json.readTree(started.getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(get("/v1/attempts/" + attemptId)
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId));
    }

    @Test
    void aQuestionIdentifiesItselfWithThePrefixedForm() throws Exception {
        var p = player();

        var started = mvc.perform(post("/v1/tournaments/" + p.tournamentId() + "/attempts")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader())
                        .with(csrf()))
                .andReturn();
        String attemptId = json.readTree(started.getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(get("/v1/attempts/" + attemptId + "/questions/1")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId").value(org.hamcrest.Matchers
                        .matchesPattern("^qst_[0-9a-f]{32}$")));
    }

    @Test
    void aTournamentListIsWrappedInThePageEnvelope() throws Exception {
        var p = player();

        mvc.perform(get("/v1/tournaments")
                        .cookie(p.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, p.workspaceHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(p.tournamentId()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }
}
