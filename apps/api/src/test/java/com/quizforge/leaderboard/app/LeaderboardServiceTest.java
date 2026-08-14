package com.quizforge.leaderboard.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.play.AttemptGraded;
import com.quizforge.play.app.AttemptService;
import com.quizforge.platform.tenancy.TenantContext;
import com.quizforge.tournament.ScoringPolicy;
import com.quizforge.tournament.app.TournamentDraft;
import com.quizforge.tournament.app.TournamentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderboardServiceTest extends AbstractIntegrationTest {

    @Autowired private LeaderboardService leaderboard;
    @Autowired private AttemptService attempts;
    @Autowired private TournamentService tournaments;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    private static final String RIGHT = "alpha";
    private static final String WRONG = "beta";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newAccount() {
        return accounts.register("user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    private record Fixture(UUID owner, UUID workspaceId, UUID tournamentId) {
    }

    private Fixture fixture(ScoringPolicy policy, int maxAttempts) {
        UUID owner = newAccount();
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
                        opens.plus(Duration.ofDays(1)), 2, 600, maxAttempts, policy));

        return new Fixture(owner, workspace.getId(), tournament.getId());
    }

    /** Plays one attempt scoring exactly {@code correct} out of 2. */
    private void play(UUID tournamentId, UUID account, int correct) {
        var attempt = attempts.start(tournamentId, account);
        for (int position = 1; position <= 2; position++) {
            attempts.answer(attempt.getId(), position,
                    position <= correct ? RIGHT : WRONG, account);
        }
        attempts.submit(attempt.getId(), account);
    }

    @Test
    void aStandingAppearsWhenAnAttemptIsGraded() {
        var f = fixture(ScoringPolicy.FIRST, 1);
        play(f.tournamentId(), f.owner(), 2);

        var board = leaderboard.standings(f.tournamentId(), 10);

        assertThat(board).hasSize(1);
        assertThat(board.get(0).score()).isEqualTo(2.0);
        assertThat(board.get(0).outOf()).isEqualTo(2);
        assertThat(board.get(0).rank()).isEqualTo(1);
    }

    @Test
    void bestPolicyTakesTheHighestAttempt() {
        var f = fixture(ScoringPolicy.BEST, 3);
        play(f.tournamentId(), f.owner(), 1);
        play(f.tournamentId(), f.owner(), 2);
        play(f.tournamentId(), f.owner(), 0);

        assertThat(leaderboard.standings(f.tournamentId(), 10).get(0).score()).isEqualTo(2.0);
    }

    @Test
    void firstPolicyIgnoresLaterAttempts() {
        var f = fixture(ScoringPolicy.FIRST, 3);
        play(f.tournamentId(), f.owner(), 1);
        play(f.tournamentId(), f.owner(), 2);

        assertThat(leaderboard.standings(f.tournamentId(), 10).get(0).score()).isEqualTo(1.0);
    }

    @Test
    void lastPolicyTakesTheMostRecentAttempt() {
        var f = fixture(ScoringPolicy.LAST, 3);
        play(f.tournamentId(), f.owner(), 2);
        play(f.tournamentId(), f.owner(), 1);

        assertThat(leaderboard.standings(f.tournamentId(), 10).get(0).score()).isEqualTo(1.0);
    }

    @Test
    void averagePolicyMeansTheAttempts() {
        var f = fixture(ScoringPolicy.AVERAGE, 3);
        play(f.tournamentId(), f.owner(), 2);
        play(f.tournamentId(), f.owner(), 1);

        assertThat(leaderboard.standings(f.tournamentId(), 10).get(0).score()).isEqualTo(1.5);
    }

    @Test
    void replayingTheSameEventDoesNotDoubleCount() {
        // Delivery is at-least-once, so the handler must be idempotent. It
        // recomputes from the attempt history rather than incrementing, which
        // makes replay a no-op by construction.
        var f = fixture(ScoringPolicy.AVERAGE, 3);
        play(f.tournamentId(), f.owner(), 2);

        var before = leaderboard.standings(f.tournamentId(), 10).get(0);

        leaderboard.on(new AttemptGraded(UUID.randomUUID(), f.tournamentId(),
                f.workspaceId(), f.owner(), 2, 2, Instant.now()));

        var after = leaderboard.standings(f.tournamentId(), 10).get(0);

        assertThat(after.attempts()).isEqualTo(before.attempts());
        assertThat(after.score()).isEqualTo(before.score());
    }

    @Test
    void higherScoresRankFirst() {
        var f = fixture(ScoringPolicy.BEST, 1);
        UUID strong = f.owner();
        UUID weak = newAccount();
        workspaces.addMember(f.workspaceId(), f.owner(), weak,
                com.quizforge.identity.domain.Role.VIEWER);

        play(f.tournamentId(), weak, 0);
        play(f.tournamentId(), strong, 2);

        var board = leaderboard.standings(f.tournamentId(), 10);

        assertThat(board).hasSize(2);
        assertThat(board.get(0).accountId()).isEqualTo(strong);
        assertThat(board.get(0).rank()).isEqualTo(1);
        assertThat(board.get(1).rank()).isEqualTo(2);
    }

    @Test
    void tiesBreakInFavourOfWhoeverFinishedFirst() {
        var f = fixture(ScoringPolicy.BEST, 1);
        UUID early = f.owner();
        UUID late = newAccount();
        workspaces.addMember(f.workspaceId(), f.owner(), late,
                com.quizforge.identity.domain.Role.VIEWER);

        play(f.tournamentId(), early, 2);
        play(f.tournamentId(), late, 2);

        var board = leaderboard.standings(f.tournamentId(), 10);

        assertThat(board.get(0).accountId())
                .as("equal scores rank by who got there first")
                .isEqualTo(early);
    }

    @Test
    void theLimitIsRespected() {
        var f = fixture(ScoringPolicy.BEST, 1);
        UUID second = newAccount();
        workspaces.addMember(f.workspaceId(), f.owner(), second,
                com.quizforge.identity.domain.Role.VIEWER);

        play(f.tournamentId(), f.owner(), 2);
        play(f.tournamentId(), second, 1);

        assertThat(leaderboard.standings(f.tournamentId(), 1)).hasSize(1);
    }
}
