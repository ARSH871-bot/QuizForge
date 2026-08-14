package com.quizforge.play;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The play module's published API.
 *
 * <p>Exposes only closed attempts. An in-progress attempt is nobody else's
 * business, and exposing one would invite a consumer to rank on a score that
 * is still moving.
 */
public interface PlayAccess {

    /** A closed attempt, as anything outside play needs to see it. */
    record GradedAttempt(UUID attemptId, int score, int outOf, Instant gradedAt) {
    }

    /**
     * Every graded or expired attempt by one account at one tournament,
     * oldest first.
     */
    List<GradedAttempt> gradedAttempts(UUID tournamentId, UUID accountId);
}
