package com.quizforge.leaderboard.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.play.app.AttemptService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenancy tests for the standings endpoint.
 *
 * <p>Written while describing the endpoint for the OpenAPI contract. Documenting
 * its authorization meant stating what it is, and it did not have one.
 */
@AutoConfigureMockMvc
class LeaderboardControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TournamentService tournaments;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;
    @Autowired private AttemptService attempts;

    private static final String RIGHT = "alpha";
    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Actor(Cookie session, String workspaceHeader, String tournamentId) {
    }

    /** A workspace with a played tournament, so a standing row exists. */
    private Actor actorWithAStanding() throws Exception {
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

        var attempt = attempts.start(tournament.getId(), accountId);
        attempts.answer(attempt.getId(), 1, RIGHT, accountId);
        attempts.submit(attempt.getId(), accountId);
        TenantContext.clear();

        var login = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        return new Actor(login.getResponse().getCookie(SessionAuthFilter.COOKIE_NAME),
                TypeId.render("wsp", workspace.getId()),
                TypeId.render("trn", tournament.getId()));
    }

    @Test
    void theOwningWorkspaceSeesItsOwnStandings() throws Exception {
        var owner = actorWithAStanding();

        mvc.perform(get("/v1/tournaments/" + owner.tournamentId() + "/standings")
                        .cookie(owner.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, owner.workspaceHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rank").value(1));
    }

    @Test
    void anotherWorkspaceCannotReadStandingsItDoesNotOwn() throws Exception {
        // 404 rather than an empty list: with the stranger's workspace in
        // scope, RLS hides the foreign tournament itself, so resolving its
        // scoring policy fails before any standing is read. Existence is not
        // disclosed, which is the better of the two safe answers.
        var owner = actorWithAStanding();
        var stranger = actorWithAStanding();

        mvc.perform(get("/v1/tournaments/" + owner.tournamentId() + "/standings")
                        .cookie(stranger.session())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, stranger.workspaceHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void omittingTheWorkspaceHeaderDoesNotExposeAnotherWorkspacesStandings() throws Exception {
        // The interesting case, and the one the endpoint did not handle.
        //
        // TenantContext is populated only when the workspace header is present.
        // With no tenant in scope, TenantAwareDataSource hands the connection
        // through as the owning role, and PostgreSQL skips RLS for superusers -
        // so the tenant filter that protects the previous test is simply absent.
        //
        // Every other endpoint survives that because it checks authorization in
        // the application layer as well. This one had no principal parameter at
        // all, so there was nothing standing between an authenticated stranger
        // and any tournament's standings.
        //
        // Before the fix this returned 200 with the owner's rows - asserted as
        // such first, so the failure was demonstrated rather than assumed.
        var owner = actorWithAStanding();
        var stranger = actorWithAStanding();

        mvc.perform(get("/v1/tournaments/" + owner.tournamentId() + "/standings")
                        .cookie(stranger.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void standingsRequireAuthentication() throws Exception {
        var owner = actorWithAStanding();

        mvc.perform(get("/v1/tournaments/" + owner.tournamentId() + "/standings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
