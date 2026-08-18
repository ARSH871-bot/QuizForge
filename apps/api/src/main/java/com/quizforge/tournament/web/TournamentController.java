package com.quizforge.tournament.web;

import com.quizforge.api.TournamentsApi;
import com.quizforge.api.model.TournamentPage;
import com.quizforge.api.model.TournamentState;
import com.quizforge.api.model.TournamentSummary;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.tournament.app.TournamentService;
import com.quizforge.tournament.domain.Tournament;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Reading tournaments. Creation arrives later in M4.
 *
 * <p>Implements {@link TournamentsApi}, generated from {@code openapi.yaml}, so
 * the path, verb, parameters and response type come from the contract rather
 * than from this file.
 */
@RestController
public class TournamentController implements TournamentsApi {

    private final TournamentService tournaments;

    public TournamentController(TournamentService tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public ResponseEntity<TournamentPage> listTournaments(String workspaceHeader) {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }

        Instant now = Instant.now();
        TournamentPage page = new TournamentPage(
                tournaments.inWorkspace(principal.workspaceId()).stream()
                        .map(t -> summarise(t, now))
                        .toList());

        // Always null until cursor pagination lands. The field exists so that
        // filling it in is additive rather than a change of top-level type.
        page.setNextCursor(null);
        return ResponseEntity.ok(page);
    }

    /** What a player needs to choose a tournament. Reveals no question content. */
    private TournamentSummary summarise(Tournament tournament, Instant now) {
        TournamentSummary summary = new TournamentSummary(
                TypeId.render("trn", tournament.getId()),
                tournament.getName(),
                TournamentState.fromValue(tournament.state(now).name()),
                tournament.getOpensAt().atOffset(ZoneOffset.UTC),
                tournament.getClosesAt().atOffset(ZoneOffset.UTC),
                tournament.getQuestionCount(),
                tournament.getMaxAttempts());
        summary.setTimeLimitSeconds(tournament.getTimeLimitSeconds());
        return summary;
    }
}
