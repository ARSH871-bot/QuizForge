package com.quizforge.tournament.web;

import com.quizforge.api.TournamentsApi;
import com.quizforge.api.model.ScoringPolicy;
import com.quizforge.api.model.TournamentDraft;
import com.quizforge.api.model.TournamentPage;
import com.quizforge.api.model.TournamentState;
import com.quizforge.api.model.TournamentSummary;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.web.PageWindow;
import com.quizforge.tournament.app.TournamentService;
import com.quizforge.tournament.domain.Tournament;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Tournaments — scheduling, amending and deleting.
 *
 * <p>State is never written. It is derived from the window and the clock on
 * every read, so the same row answers {@code SCHEDULED} and later {@code OPEN}
 * with nothing having touched it. Every rule below is expressed against that
 * derived state rather than against a stored flag that could disagree with the
 * calendar.
 */
@RestController
public class TournamentController implements TournamentsApi {

    private final TournamentService tournaments;

    public TournamentController(TournamentService tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public ResponseEntity<TournamentPage> listTournaments(String workspaceHeader,
                                                          Integer limit, String cursor) {
        Principal principal = requireWorkspace();
        PageWindow window = PageWindow.of(limit, cursor);

        Instant now = Instant.now();
        var slice = window.slice(tournaments.inWorkspace(principal.workspaceId(), window),
                Tournament::getId);

        TournamentPage page = new TournamentPage(
                slice.data().stream().map(t -> summarise(t, now)).toList());
        page.setNextCursor(slice.nextCursor());
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<TournamentSummary> createTournament(TournamentDraft draft,
                                                              String idempotencyKey,
                                                              String workspaceHeader) {
        Principal principal = requireAccount();

        var created = tournaments.create(principal.workspaceId(), principal.accountId(),
                toDomain(draft));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(summarise(created, Instant.now()));
    }

    @Override
    public ResponseEntity<TournamentSummary> getTournament(String tournamentId,
                                                           String workspaceHeader) {
        requireWorkspace();
        return ResponseEntity.ok(summarise(
                tournaments.requireById(TypeId.parse("trn", tournamentId)), Instant.now()));
    }

    @Override
    public ResponseEntity<TournamentSummary> updateTournament(String tournamentId,
                                                              TournamentDraft draft,
                                                              String idempotencyKey,
                                                              String workspaceHeader) {
        Principal principal = requireAccount();

        var updated = tournaments.update(TypeId.parse("trn", tournamentId),
                principal.accountId(), toDomain(draft));

        return ResponseEntity.ok(summarise(updated, Instant.now()));
    }

    @Override
    public ResponseEntity<Void> deleteTournament(String tournamentId, String idempotencyKey,
        String workspaceHeader) {
        Principal principal = requireAccount();
        tournaments.delete(TypeId.parse("trn", tournamentId), principal.accountId());
        return ResponseEntity.noContent().build();
    }

    private com.quizforge.tournament.app.TournamentDraft toDomain(TournamentDraft draft) {
        UUID bankId = TypeId.parse("bnk", draft.getBankId());
        ScoringPolicy policy = draft.getScoringPolicy();

        return new com.quizforge.tournament.app.TournamentDraft(
                draft.getName(),
                bankId,
                draft.getOpensAt() == null ? null : draft.getOpensAt().toInstant(),
                draft.getClosesAt() == null ? null : draft.getClosesAt().toInstant(),
                draft.getQuestions(),
                draft.getTimeLimitSeconds(),
                draft.getMaxAttempts(),
                policy == null
                        ? null
                        : com.quizforge.tournament.ScoringPolicy.valueOf(policy.getValue()));
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
        summary.setBankId(TypeId.render("bnk", tournament.getBankId()));
        summary.setScoringPolicy(ScoringPolicy.fromValue(tournament.getScoringPolicy().name()));
        return summary;
    }

    /**
     * Reads need a workspace and nothing more. An API key names a workspace, so
     * a server integration can list and read tournaments - that is most of what
     * an integration does.
     */
    private Principal requireWorkspace() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }
        return principal;
    }

    /**
     * Writes additionally need an account, because they are audited against a
     * person. A key identifies a workspace and nobody in particular, so there
     * would be no one to attribute a schedule change to.
     */
    private Principal requireAccount() {
        Principal principal = requireWorkspace();
        if (principal.accountId() == null) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "managing tournaments requires a signed-in account, not an API key");
        }
        return principal;
    }
}
