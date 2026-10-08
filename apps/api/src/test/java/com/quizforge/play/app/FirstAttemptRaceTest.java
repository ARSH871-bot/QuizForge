package com.quizforge.play.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.tenancy.TenantContext;
import com.quizforge.tournament.ScoringPolicy;
import com.quizforge.tournament.app.TournamentDraft;
import com.quizforge.tournament.app.TournamentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An organiser changing a tournament at the moment its first player starts.
 *
 * <p>A tournament can be amended or deleted until somebody plays it. Checking
 * "nobody has played" and then acting is a race unless the check and the first
 * attempt are serialised: an attempt still in flight is invisible to the count,
 * so without a lock the organiser sees zero, and then either deletes the
 * attempt along with the tournament (the foreign key cascades) or changes the
 * rules under a player who has already started.
 *
 * <p>Each test holds a player's attempt open, uncommitted, and makes the change
 * while it is in flight. The change must wait for the attempt, see it, and be
 * refused. Both fail if the locks are removed.
 */
class FirstAttemptRaceTest extends AbstractIntegrationTest {

    @Autowired private TournamentService tournaments;
    @Autowired private AttemptService attempts;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;
    @Autowired private PlatformTransactionManager transactions;

    /** How long the player's attempt stays open before it commits. */
    private static final long HELD_MILLIS = 750;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Fixture(UUID actor, UUID workspaceId, UUID bankId, UUID tournamentId) {
    }

    private Fixture openTournament() {
        UUID owner = accounts.register("race-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Organiser").getId();
        var workspace = workspaces.create(owner, "Race " + UUID.randomUUID());
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");
        for (int i = 0; i < 2; i++) {
            questions.author(bank.getId(), owner, QuestionType.SINGLE_CHOICE, "Question " + i + "?",
                    new ChoicePayload(List.of(new ChoicePayload.Option("right", true),
                            new ChoicePayload.Option("wrong", false))),
                    "EASY");
        }
        Instant opens = Instant.now().minus(Duration.ofMinutes(1));
        var tournament = tournaments.create(workspace.getId(), owner, new TournamentDraft("Race",
                bank.getId(), opens, opens.plus(Duration.ofDays(1)), 2, 600, 1, ScoringPolicy.BEST));
        TenantContext.clear();
        return new Fixture(owner, workspace.getId(), bank.getId(), tournament.getId());
    }

    /**
     * Starts an attempt and holds its transaction open; returns once the
     * attempt exists but before it commits.
     */
    private Future<?> playerStartsAndHolds(ExecutorService pool, Fixture f) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Future<?> player = pool.submit(asTenant(f, () -> new TransactionTemplate(transactions).execute(status -> {
            attempts.start(f.tournamentId(), f.actor());
            started.countDown();
            try {
                Thread.sleep(HELD_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        })));
        assertThat(started.await(10, TimeUnit.SECONDS)).as("the attempt started").isTrue();
        return player;
    }

    private <T> Callable<T> asTenant(Fixture f, Callable<T> work) {
        return () -> {
            TenantContext.set(f.workspaceId());
            try {
                return work.call();
            } finally {
                TenantContext.clear();
            }
        };
    }

    /** Runs the organiser's change; returns what it threw, or null if it succeeded. */
    private Throwable organiser(ExecutorService pool, Fixture f, Runnable change) throws Exception {
        return pool.submit(asTenant(f, () -> {
            try {
                change.run();
                return (Throwable) null;
            } catch (RuntimeException e) {
                return e;
            }
        })).get(15, TimeUnit.SECONDS);
    }

    @Test
    void aDeleteRacingTheFirstAttemptWaitsForItAndIsRefused() throws Exception {
        var f = openTournament();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> player = playerStartsAndHolds(pool, f);

            Throwable refused = organiser(pool, f, () -> tournaments.delete(f.tournamentId(), f.actor()));
            player.get(15, TimeUnit.SECONDS);

            // Without the lock, the count saw nothing, the delete waited on the
            // attempt's foreign key, and then cascaded the attempt away.
            assertThat(refused).isInstanceOf(ApiException.class).hasMessageContaining("attempt");
            TenantContext.set(f.workspaceId());
            assertThat(attempts.forTournament(f.tournamentId())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAmendmentRacingTheFirstAttemptWaitsForItAndIsRefused() throws Exception {
        var f = openTournament();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> player = playerStartsAndHolds(pool, f);

            Instant opens = Instant.now().minus(Duration.ofMinutes(1));
            Throwable refused = organiser(pool, f, () -> tournaments.update(f.tournamentId(), f.actor(),
                    new TournamentDraft("Changed", f.bankId(), opens, opens.plus(Duration.ofDays(2)),
                            1, 60, 3, ScoringPolicy.AVERAGE)));
            player.get(15, TimeUnit.SECONDS);

            // Without the lock, the count saw nothing and the rules changed
            // under a player who had already started under the old ones.
            assertThat(refused).isInstanceOf(ApiException.class).hasMessageContaining("attempt");
            TenantContext.set(f.workspaceId());
            var unchanged = tournaments.requireById(f.tournamentId());
            assertThat(unchanged.getName()).isEqualTo("Race");
            assertThat(unchanged.getQuestionCount()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }
}
