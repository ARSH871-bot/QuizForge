package com.quizforge.tournament.app;

import com.quizforge.tournament.ScoringPolicy;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything needed to schedule a tournament.
 *
 * <p>A record rather than a long parameter list: seven arguments of which four
 * are numbers is exactly the signature where two get transposed silently.
 */
public record TournamentDraft(
        String name,
        UUID bankId,
        Instant opensAt,
        Instant closesAt,
        int questionCount,
        Integer timeLimitSeconds,
        int maxAttempts,
        ScoringPolicy scoringPolicy) {
}
