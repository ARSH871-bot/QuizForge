package com.quizforge.tournament;

import java.util.UUID;

/**
 * The tournament module's published API.
 *
 * <p>Other modules read tournaments through this interface rather than
 * reaching into {@code tournament.app} or {@code tournament.domain}. Spring
 * Modulith exposes only a module's root package, and widening
 * {@code allowedDependencies} to reach internals would defeat the boundary
 * rather than respect it.
 */
public interface TournamentAccess {

    /**
     * The subset of a tournament that playing one requires.
     *
     * <p>A projection rather than the entity, so the play module cannot
     * accumulate opinions about scheduling, naming or slugs — and so changing
     * any of those touches nothing outside this module.
     */
    record PlayableTournament(
            UUID id,
            UUID workspaceId,
            UUID bankId,
            int questionCount,
            Integer timeLimitSeconds,
            int maxAttempts) {
    }

    /**
     * Returns the tournament if it is currently open for play.
     *
     * <p>Throws when it is scheduled or closed. The open/closed decision lives
     * here rather than in the caller, because it is derived from this module's
     * own scheduling fields and callers should not re-derive it.
     */
    PlayableTournament requireOpen(UUID tournamentId);

    /**
     * How multiple attempts collapse into one standing.
     *
     * <p>Read at leaderboard time rather than stamped onto a standing, so
     * changing a tournament's policy takes effect without recomputing history.
     */
    ScoringPolicy scoringPolicyOf(UUID tournamentId);

    /** Throws {@code NOT_FOUND} unless the tournament exists in the caller's workspace. */
    void requireVisible(UUID tournamentId);
}
