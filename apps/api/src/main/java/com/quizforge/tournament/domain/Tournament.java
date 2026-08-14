package com.quizforge.tournament.domain;

import com.quizforge.tournament.ScoringPolicy;
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
 * A scheduled run of questions drawn from a bank, open for a window.
 *
 * <p>The question set is not chosen here — it is drawn per attempt and frozen
 * into that attempt, so what a player saw is pinned by construction. This
 * entity only records how many to draw.
 */
@Entity
@Table(name = "tournament")
public class Tournament {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "bank_id", nullable = false)
    private UUID bankId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 140)
    private String slug;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Column(name = "question_count", nullable = false)
    private int questionCount;

    @Column(name = "time_limit_seconds")
    private Integer timeLimitSeconds;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Enumerated(EnumType.STRING)
    @Column(name = "scoring_policy", nullable = false, length = 8)
    private ScoringPolicy scoringPolicy;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected Tournament() {
    }

    public Tournament(UUID id, UUID workspaceId, UUID bankId, String name, String slug,
                      Instant opensAt, Instant closesAt, int questionCount,
                      Integer timeLimitSeconds, int maxAttempts, ScoringPolicy scoringPolicy,
                      UUID createdBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.bankId = bankId;
        this.name = name;
        this.slug = slug;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.questionCount = questionCount;
        this.timeLimitSeconds = timeLimitSeconds;
        this.maxAttempts = maxAttempts;
        this.scoringPolicy = scoringPolicy;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getBankId() { return bankId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public Instant getOpensAt() { return opensAt; }
    public Instant getClosesAt() { return closesAt; }
    public int getQuestionCount() { return questionCount; }
    public Integer getTimeLimitSeconds() { return timeLimitSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public ScoringPolicy getScoringPolicy() { return scoringPolicy; }
    public UUID getCreatedBy() { return createdBy; }

    /**
     * Derived, never stored. Takes the instant as a parameter rather than
     * reading the clock so that it is testable and so a caller grading a past
     * attempt can ask what the state was then.
     */
    public TournamentState state(Instant at) {
        if (at.isBefore(opensAt)) {
            return TournamentState.SCHEDULED;
        }
        if (at.isAfter(closesAt)) {
            return TournamentState.CLOSED;
        }
        return TournamentState.OPEN;
    }

    public boolean isOpen(Instant at) {
        return state(at) == TournamentState.OPEN;
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    /**
     * Reschedules a tournament that has not yet opened. Moving the window of a
     * tournament already in progress would change the rules under players who
     * have started.
     */
    public void reschedule(Instant opensAt, Instant closesAt, Instant now) {
        if (state(now) != TournamentState.SCHEDULED) {
            throw new IllegalStateException("only a scheduled tournament can be rescheduled");
        }
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.updatedAt = Instant.now();
    }
}
