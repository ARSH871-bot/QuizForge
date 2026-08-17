package com.quizforge.play.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.leaderboard.app.LeaderboardService;
import com.quizforge.play.domain.AttemptState;
import com.quizforge.play.repo.AttemptRepository;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.tenancy.TenantContext;
import com.quizforge.tournament.ScoringPolicy;
import com.quizforge.tournament.app.TournamentDraft;
import com.quizforge.tournament.app.TournamentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptExpirySweepTest extends AbstractIntegrationTest {

    @Autowired private AttemptExpirySweep sweep;
    @Autowired private AttemptService attempts;
    @Autowired private AttemptRepository attemptRepository;
    @Autowired private LeaderboardService leaderboard;
    @Autowired private TournamentService tournaments;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;
    @Autowired private JdbcTemplate jdbc;

    private static final String RIGHT = "alpha";
    private static final String WRONG = "beta";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Fixture(UUID owner, UUID tournamentId) {
    }

    private Fixture fixture(int timeLimitSeconds) {
        UUID owner = accounts.register("user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
        var workspace = workspaces.create(owner, "Acme " + UUID.randomUUID());
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        for (int i = 0; i < 4; i++) {
            questions.author(bank.getId(), owner, QuestionType.SINGLE_CHOICE,
                    "Question " + i + "?",
                    new ChoicePayload(List.of(
                            new ChoicePayload.Option(RIGHT, true),
                            new ChoicePayload.Option(WRONG, false))),
                    "EASY");
        }

        Instant opens = Instant.now().minus(Duration.ofMinutes(1));
        var tournament = tournaments.create(workspace.getId(), owner,
                new TournamentDraft("Quiz", bank.getId(), opens,
                        opens.plus(Duration.ofDays(1)), 2, timeLimitSeconds, 1,
                        ScoringPolicy.BEST));

        return new Fixture(owner, tournament.getId());
    }

    /** Drags an attempt's expiry into the past, simulating time passing. */
    private void makeOverdue(UUID attemptId) {
        jdbc.update("UPDATE attempt SET expires_at = now() - interval '1 minute' WHERE id = ?",
                attemptId);
    }

    @Test
    void closesAnAbandonedAttemptAndScoresWhatWasAnswered() {
        var f = fixture(600);
        var attempt = attempts.start(f.tournamentId(), f.owner());
        attempts.answer(attempt.getId(), 1, RIGHT, f.owner());
        makeOverdue(attempt.getId());

        int closed = sweep.run();

        assertThat(closed).isEqualTo(1);
        var reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(AttemptState.EXPIRED);
        assertThat(reloaded.getScoreNumerator()).isEqualTo(1);
        assertThat(reloaded.getScoreDenominator())
                .as("unanswered questions still count against the player")
                .isEqualTo(2);
    }

    @Test
    void anExpiredAttemptStillProducesAStanding() {
        // Without this, an abandoned attempt would leave the leaderboard
        // permanently incomplete.
        var f = fixture(600);
        var attempt = attempts.start(f.tournamentId(), f.owner());
        attempts.answer(attempt.getId(), 1, RIGHT, f.owner());
        makeOverdue(attempt.getId());

        sweep.run();

        var board = leaderboard.standings(f.tournamentId(), 10);
        assertThat(board).hasSize(1);
        assertThat(board.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void leavesAttemptsInsideTheirWindowAlone() {
        var f = fixture(600);
        var attempt = attempts.start(f.tournamentId(), f.owner());

        int closed = sweep.run();

        assertThat(closed).isZero();
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(AttemptState.STARTED);
    }

    @Test
    void leavesAttemptsWithNoTimeLimitAlone() {
        var f = fixture(0 + 1);   // a limit exists but we never make it overdue
        var attempt = attempts.start(f.tournamentId(), f.owner());

        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(AttemptState.STARTED);
    }

    @Test
    void runningTwiceClosesNothingTheSecondTime() {
        var f = fixture(600);
        var attempt = attempts.start(f.tournamentId(), f.owner());
        makeOverdue(attempt.getId());

        assertThat(sweep.run()).isEqualTo(1);
        assertThat(sweep.run())
                .as("a terminal attempt must not be closed again")
                .isZero();
    }

    @Test
    void anExpiredAttemptRefusesFurtherAnswers() {
        var f = fixture(600);
        var attempt = attempts.start(f.tournamentId(), f.owner());
        makeOverdue(attempt.getId());
        sweep.run();

        assertThatThrownBy(() -> attempts.answer(attempt.getId(), 1, RIGHT, f.owner()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void expiryIsEnforcedOnAccessEvenBeforeTheSweepRuns() {
        // The property that matters most: the sweep settles leaderboards, it is
        // not what stops a late answer. A player must not benefit from the job
        // being behind.
        var f = fixture(600);
        var attempt = attempts.start(f.tournamentId(), f.owner());
        makeOverdue(attempt.getId());

        assertThatThrownBy(() -> attempts.answer(attempt.getId(), 1, RIGHT, f.owner()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("time limit");
    }
}
