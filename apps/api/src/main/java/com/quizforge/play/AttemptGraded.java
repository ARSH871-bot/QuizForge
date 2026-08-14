package com.quizforge.play;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an attempt reaches a terminal state, whether by submission or
 * expiry.
 *
 * <p>Consumers must treat this as at-least-once: the same attempt may be
 * announced twice. Handlers are required to be idempotent rather than the
 * publisher guaranteeing exactly-once, which it cannot.
 */
public record AttemptGraded(
        UUID attemptId,
        UUID tournamentId,
        UUID workspaceId,
        UUID accountId,
        int score,
        int outOf,
        Instant gradedAt) {
}
