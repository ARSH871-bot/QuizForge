package com.quizforge.tournament.web;

import com.quizforge.api.JoinApi;
import com.quizforge.api.model.Enrolment;
import com.quizforge.api.model.PublicTournament;
import com.quizforge.api.model.Role;
import com.quizforge.api.model.TournamentState;
import com.quizforge.api.model.Workspace;
import com.quizforge.identity.AccountDirectory;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.identity.WorkspaceEnrolment;
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
 * Share links.
 *
 * <p>Both operations run with no workspace in scope, because the person holding
 * the link is not a member yet. That makes this the one place a tournament is
 * read by someone outside its workspace, so the public card is built
 * field by field: nothing here reaches the bank, the questions or the
 * standings.
 */
@RestController
public class JoinController implements JoinApi {

    private final TournamentService tournaments;
    private final AccountDirectory directory;
    private final WorkspaceEnrolment enrolment;

    public JoinController(TournamentService tournaments, AccountDirectory directory,
                          WorkspaceEnrolment enrolment) {
        this.tournaments = tournaments;
        this.directory = directory;
        this.enrolment = enrolment;
    }

    @Override
    public ResponseEntity<PublicTournament> getPublicTournament(String tournamentId) {
        Tournament tournament = tournaments.requireById(TypeId.parse("trn", tournamentId));
        String organiser = directory.workspaceNameOf(tournament.getWorkspaceId())
                .orElseThrow(() -> ApiException.notFound("tournament"));

        PublicTournament card = new PublicTournament(
                TypeId.render("trn", tournament.getId()),
                tournament.getName(),
                organiser,
                TournamentState.fromValue(tournament.state(Instant.now()).name()),
                tournament.getOpensAt().atOffset(ZoneOffset.UTC),
                tournament.getClosesAt().atOffset(ZoneOffset.UTC),
                tournament.getQuestionCount(),
                tournament.getMaxAttempts());
        card.setTimeLimitSeconds(tournament.getTimeLimitSeconds());
        return ResponseEntity.ok(card);
    }

    @Override
    public ResponseEntity<Enrolment> joinTournament(String tournamentId) {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.accountId() == null) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED,
                    "sign in to join a tournament");
        }

        Tournament tournament = tournaments.requireById(TypeId.parse("trn", tournamentId));
        var enrolled = enrolment.enrolAsPlayer(principal.accountId(), tournament.getWorkspaceId());

        Workspace workspace = new Workspace(
                TypeId.render("wsp", enrolled.workspaceId()),
                enrolled.name(),
                enrolled.slug(),
                Role.fromValue(enrolled.role()));
        return ResponseEntity.ok(new Enrolment(workspace, enrolled.joined()));
    }
}
