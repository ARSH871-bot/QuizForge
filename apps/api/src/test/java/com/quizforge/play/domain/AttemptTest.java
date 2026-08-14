package com.quizforge.play.domain;

import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.id.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure domain tests — no Spring, no database. The state machine is the part
 * that must be provably correct, and it is testable in isolation.
 */
class AttemptTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    private static Attempt started(int questionCount, Duration limit) {
        return new Attempt(UuidV7.generate(), UuidV7.generate(), UuidV7.generate(),
                UuidV7.generate(), questionCount, START,
                limit == null ? null : START.plus(limit));
    }

    @Test
    void beginsInStartedStateWithAFrozenDenominator() {
        var attempt = started(10, Duration.ofMinutes(10));

        assertThat(attempt.getState()).isEqualTo(AttemptState.STARTED);
        assertThat(attempt.getScoreDenominator())
                .as("the denominator is frozen at creation, not derived from the bank later")
                .isEqualTo(10);
        assertThat(attempt.getScoreNumerator()).isZero();
    }

    @Test
    void acceptsAnswersWhileStartedAndWithinTheWindow() {
        var attempt = started(3, Duration.ofMinutes(10));

        attempt.requireAnswerable(START.plusSeconds(30));   // does not throw
    }

    @Test
    void refusesAnswersAfterExpiry() {
        var attempt = started(3, Duration.ofMinutes(10));

        assertThat(attempt.isExpired(START.plus(Duration.ofMinutes(11)))).isTrue();
        assertThatThrownBy(() -> attempt.requireAnswerable(START.plus(Duration.ofMinutes(11))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("time limit");
    }

    @Test
    void anAttemptWithNoTimeLimitNeverExpires() {
        var attempt = started(3, null);

        assertThat(attempt.isExpired(START.plus(Duration.ofDays(3650)))).isFalse();
        attempt.requireAnswerable(START.plus(Duration.ofDays(3650)));
    }

    @Test
    void submittingGradesAndMovesToGraded() {
        var attempt = started(4, Duration.ofMinutes(10));

        attempt.submit(3, START.plusSeconds(120));

        assertThat(attempt.getState()).isEqualTo(AttemptState.GRADED);
        assertThat(attempt.getScoreNumerator()).isEqualTo(3);
        assertThat(attempt.getScoreDenominator()).isEqualTo(4);
        assertThat(attempt.getSubmittedAt()).isEqualTo(START.plusSeconds(120));
        assertThat(attempt.getGradedAt()).isNotNull();
    }

    @Test
    void submittingTwiceIsRejectedRatherThanDoubleCounted() {
        var attempt = started(4, Duration.ofMinutes(10));
        attempt.submit(3, START.plusSeconds(120));

        assertThatThrownBy(() -> attempt.submit(4, START.plusSeconds(130)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been submitted");

        assertThat(attempt.getScoreNumerator())
                .as("the first result stands")
                .isEqualTo(3);
    }

    @Test
    void answeringAfterSubmissionIsRejected() {
        var attempt = started(4, Duration.ofMinutes(10));
        attempt.submit(2, START.plusSeconds(60));

        assertThatThrownBy(() -> attempt.requireAnswerable(START.plusSeconds(90)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been submitted");
    }

    @Test
    void expiringGradesWhateverWasAnswered() {
        var attempt = started(5, Duration.ofMinutes(10));

        attempt.expire(2, START.plus(Duration.ofMinutes(11)));

        assertThat(attempt.getState()).isEqualTo(AttemptState.EXPIRED);
        assertThat(attempt.getScoreNumerator()).isEqualTo(2);
        assertThat(attempt.getScoreDenominator())
                .as("unanswered questions count as wrong, they do not shrink the denominator")
                .isEqualTo(5);
    }

    @Test
    void anAlreadyGradedAttemptCannotBeExpired() {
        var attempt = started(5, Duration.ofMinutes(10));
        attempt.submit(5, START.plusSeconds(60));

        assertThatThrownBy(() -> attempt.expire(0, START.plus(Duration.ofMinutes(11))))
                .isInstanceOf(ApiException.class);

        assertThat(attempt.getState()).isEqualTo(AttemptState.GRADED);
        assertThat(attempt.getScoreNumerator()).isEqualTo(5);
    }

    @Test
    void scoreCannotExceedTheDenominator() {
        var attempt = started(3, Duration.ofMinutes(10));

        assertThatThrownBy(() -> attempt.submit(4, START.plusSeconds(60)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void percentageIsDerivedNotStored() {
        var attempt = started(8, Duration.ofMinutes(10));
        attempt.submit(6, START.plusSeconds(60));

        assertThat(attempt.percentage()).isEqualTo(75.0);
    }

    @Test
    void percentageOfAnUngradedAttemptIsZeroRatherThanUndefined() {
        var attempt = started(8, Duration.ofMinutes(10));

        assertThat(attempt.percentage()).isZero();
    }

    @Test
    void expiryIsComputedFromTheServerSideStartNotAClientValue() {
        // The prototype trusted whatever the client sent. The expiry instant is
        // set once at creation from the server clock and never accepted from a
        // request, which is what makes the time limit enforceable at all.
        var attempt = started(3, Duration.ofMinutes(5));

        assertThat(attempt.getExpiresAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void identifiesWhichAccountAndTournamentItBelongsTo() {
        UUID tournamentId = UuidV7.generate();
        UUID accountId = UuidV7.generate();
        var attempt = new Attempt(UuidV7.generate(), tournamentId, UuidV7.generate(),
                accountId, 5, START, START.plus(Duration.ofMinutes(10)));

        assertThat(attempt.getTournamentId()).isEqualTo(tournamentId);
        assertThat(attempt.getAccountId()).isEqualTo(accountId);
    }
}
