package com.quizforge.leaderboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One account's aggregated result at one tournament.
 *
 * <p>Stores raw aggregates rather than a single resolved score. The scoring
 * policy is applied when the leaderboard is read, so changing a tournament's
 * policy takes effect immediately instead of requiring history to be recomputed.
 */
@Entity
@Table(name = "standing")
@IdClass(Standing.Key.class)
public class Standing {

    public static class Key implements Serializable {
        private UUID tournamentId;
        private UUID accountId;

        public Key() {
        }

        public Key(UUID tournamentId, UUID accountId) {
            this.tournamentId = tournamentId;
            this.accountId = accountId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(tournamentId, key.tournamentId)
                    && Objects.equals(accountId, key.accountId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tournamentId, accountId);
        }
    }

    @Id
    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "best_score", nullable = false)
    private int bestScore;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    @Column(name = "first_score", nullable = false)
    private int firstScore;

    @Column(name = "last_score", nullable = false)
    private int lastScore;

    @Column(name = "out_of", nullable = false)
    private int outOf;

    @Column(name = "first_graded_at", nullable = false)
    private Instant firstGradedAt;

    @Column(name = "last_graded_at", nullable = false)
    private Instant lastGradedAt;

    protected Standing() {
    }

    public Standing(UUID tournamentId, UUID accountId, UUID workspaceId) {
        this.tournamentId = tournamentId;
        this.accountId = accountId;
        this.workspaceId = workspaceId;
    }

    public UUID getTournamentId() { return tournamentId; }
    public UUID getAccountId() { return accountId; }
    public int getAttempts() { return attempts; }
    public int getBestScore() { return bestScore; }
    public int getTotalScore() { return totalScore; }
    public int getFirstScore() { return firstScore; }
    public int getLastScore() { return lastScore; }
    public int getOutOf() { return outOf; }
    public Instant getFirstGradedAt() { return firstGradedAt; }
    public Instant getLastGradedAt() { return lastGradedAt; }

    /**
     * Replaces every aggregate from the full attempt history.
     *
     * <p>Recomputing rather than incrementing is what makes the listener
     * idempotent: replaying the same event produces the same row, because the
     * row is a function of the attempts rather than of how many events arrived.
     */
    public void recomputeFrom(int attempts, int bestScore, int totalScore,
                              int firstScore, int lastScore, int outOf,
                              Instant firstGradedAt, Instant lastGradedAt) {
        this.attempts = attempts;
        this.bestScore = bestScore;
        this.totalScore = totalScore;
        this.firstScore = firstScore;
        this.lastScore = lastScore;
        this.outOf = outOf;
        this.firstGradedAt = firstGradedAt;
        this.lastGradedAt = lastGradedAt;
    }
}
