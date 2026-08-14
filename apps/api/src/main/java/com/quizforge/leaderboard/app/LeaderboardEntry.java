package com.quizforge.leaderboard.app;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a ranked leaderboard.
 *
 * <p>{@code score} is a double because AVERAGE produces fractions; the other
 * policies yield whole numbers through the same field rather than needing a
 * second shape.
 *
 * <p>{@code firstGradedAt} is carried because it is the tie-break, and a
 * consumer that renders ties needs to be able to explain the order.
 */
public record LeaderboardEntry(
        UUID accountId,
        double score,
        int outOf,
        int attempts,
        Instant firstGradedAt,
        int rank) {
}
