package com.quizforge.tournament.web;

import com.quizforge.api.JoinApi;
import com.quizforge.api.model.Enrolment;
import com.quizforge.api.model.GuestJoinRequest;
import com.quizforge.api.model.PublicTournament;
import com.quizforge.api.model.Role;
import com.quizforge.api.model.TournamentState;
import com.quizforge.api.model.Workspace;
import com.quizforge.identity.AccountDirectory;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.identity.SessionCookies;
import com.quizforge.identity.WorkspaceEnrolment;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.tournament.app.TournamentService;
import com.quizforge.tournament.domain.Tournament;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

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
    private final SessionCookies cookies;
    private final HttpServletRequest http;

    public JoinController(TournamentService tournaments, AccountDirectory directory,
                          WorkspaceEnrolment enrolment, SessionCookies cookies,
                          HttpServletRequest http) {
        this.tournaments = tournaments;
        this.directory = directory;
        this.enrolment = enrolment;
        this.cookies = cookies;
        this.http = http;
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
        card.setAllowGuests(tournament.isOpenToGuests());
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

    @Override
    public ResponseEntity<Enrolment> joinTournamentAsGuest(String tournamentId,
                                                           GuestJoinRequest request) {
        Tournament tournament = tournaments.requireById(TypeId.parse("trn", tournamentId));

        // Someone already signed in joins as themselves; a second identity
        // would only split their attempts across two names.
        Principal principal = CurrentPrincipal.get();
        if (principal != null && principal.accountId() != null) {
            return joinTournament(tournamentId);
        }

        if (!tournament.isOpenToGuests()) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "this tournament needs an account to play");
        }

        var guest = enrolment.enrolAsGuest(tournament.getWorkspaceId(), request.getNickname(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
        var enrolled = guest.enrolled();
        Workspace workspace = new Workspace(
                TypeId.render("wsp", enrolled.workspaceId()),
                enrolled.name(),
                enrolled.slug(),
                Role.fromValue(enrolled.role()));
        return ResponseEntity.ok()
                .header("Set-Cookie", cookies.issue(guest.sessionToken(), guest.sessionLifetime()).toString())
                .body(new Enrolment(workspace, true));
    }
}
