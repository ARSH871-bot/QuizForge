package com.quizforge.play.domain;

import com.quizforge.platform.error.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One player's run at a tournament, and the aggregate root for play.
 *
 * <p>This model replaces the prototype's disconnected {@code Participation} row
 * and {@code Score} number, which between them fixed six defects at once:
 *
 * <ul>
 *   <li>No resumability — an attempt is durable state, so a disconnect loses
 *       nothing
 *   <li>Unlimited resubmission — submission is a state transition, not an
 *       insert
 *   <li>Score denominator drift — {@code scoreDenominator} is frozen here at
 *       creation, so a player is never graded against questions they did not
 *       see
 *   <li>The paginated flow recording nothing — responses are written as the
 *       player advances
 *   <li>Correct answers reaching the client — grading happens server-side and
 *       only the outcome is stored
 *   <li>Unenforceable time limits — {@code expiresAt} is computed once from the
 *       server clock and never accepted from a request
 * </ul>
 *
 * <p>All mutation goes through the guarded methods below. There are no setters.
 */
@Entity
@Table(name = "attempt")
public class Attempt {

    @Id
    private UUID id;

    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttemptState state = AttemptState.STARTED;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "graded_at")
    private Instant gradedAt;

    /** Null means no time limit. Never derived from a client-supplied value. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "score_numerator", nullable = false)
    private int scoreNumerator;

    @Column(name = "score_denominator", nullable = false)
    private int scoreDenominator;

    @Version
    private long version;

    protected Attempt() {
    }

    public Attempt(UUID id, UUID tournamentId, UUID workspaceId, UUID accountId,
                   int questionCount, Instant startedAt, Instant expiresAt) {
        this.id = id;
        this.tournamentId = tournamentId;
        this.workspaceId = workspaceId;
        this.accountId = accountId;
        this.scoreDenominator = questionCount;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getTournamentId() { return tournamentId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getAccountId() { return accountId; }
    public AttemptState getState() { return state; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getGradedAt() { return gradedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getScoreNumerator() { return scoreNumerator; }
    public int getScoreDenominator() { return scoreDenominator; }

    /** Percentage, derived rather than stored so it can never disagree with the score. */
    public double percentage() {
        if (scoreDenominator == 0) {
            return 0.0;
        }
        return (scoreNumerator * 100.0) / scoreDenominator;
    }

    public boolean isExpired(Instant at) {
        return expiresAt != null && at.isAfter(expiresAt);
    }

    /**
     * Guards every answer. Checking expiry on access rather than only in a
     * background sweep is what makes the limit enforceable: a job that runs
     * late would otherwise let a player submit after time.
     */
    public void requireAnswerable(Instant at) {
        if (state.isTerminal()) {
            throw ApiException.invalid(state == AttemptState.GRADED
                    ? "this attempt has already been submitted"
                    : "this attempt has expired");
        }
        if (isExpired(at)) {
            throw ApiException.invalid("the time limit for this attempt has passed");
        }
    }

    /** Submits and grades in one transition. */
    public void submit(int correctCount, Instant at) {
        if (state.isTerminal()) {
            throw ApiException.invalid("this attempt has already been submitted");
        }
        applyScore(correctCount);
        this.submittedAt = at;
        this.gradedAt = Instant.now();
        this.state = AttemptState.GRADED;
    }

    /** Closes an abandoned attempt, scoring whatever was answered. */
    public void expire(int correctCount, Instant at) {
        if (state.isTerminal()) {
            throw ApiException.invalid("this attempt is already " + state.name().toLowerCase());
        }
        applyScore(correctCount);
        this.submittedAt = at;
        this.gradedAt = Instant.now();
        this.state = AttemptState.EXPIRED;
    }

    private void applyScore(int correctCount) {
        if (correctCount < 0) {
            throw ApiException.invalid("a score cannot be negative");
        }
        if (correctCount > scoreDenominator) {
            throw ApiException.invalid("a score of " + correctCount
                    + " cannot exceed the " + scoreDenominator + " questions asked");
        }
        this.scoreNumerator = correctCount;
    }
}
