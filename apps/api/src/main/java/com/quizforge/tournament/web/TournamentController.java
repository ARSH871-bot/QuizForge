package com.quizforge.tournament.web;

import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.tournament.app.TournamentService;
import com.quizforge.tournament.domain.Tournament;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Reading tournaments. Creation arrives in M4, generated from the OpenAPI
 * contract rather than hand-written twice.
 */
@RestController
@RequestMapping("/v1/tournaments")
public class TournamentController {

    /** What a player needs to choose a tournament. Reveals no question content. */
    public record TournamentSummary(String id, String name, String state,
                                    Instant opensAt, Instant closesAt,
                                    int questions, Integer timeLimitSeconds,
                                    int maxAttempts) {
    }

    private final TournamentService tournaments;

    public TournamentController(TournamentService tournaments) {
        this.tournaments = tournaments;
    }

    @GetMapping
    public List<TournamentSummary> list(@AuthenticationPrincipal Principal principal) {
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }
        Instant now = Instant.now();
        return tournaments.inWorkspace(principal.workspaceId()).stream()
                .map(t -> summarise(t, now))
                .toList();
    }

    private TournamentSummary summarise(Tournament tournament, Instant now) {
        return new TournamentSummary(
                TypeId.render("trn", tournament.getId()),
                tournament.getName(),
                tournament.state(now).name(),
                tournament.getOpensAt(),
                tournament.getClosesAt(),
                tournament.getQuestionCount(),
                tournament.getTimeLimitSeconds(),
                tournament.getMaxAttempts());
    }
}
