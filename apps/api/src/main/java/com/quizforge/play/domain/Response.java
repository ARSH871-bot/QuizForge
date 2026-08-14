package com.quizforge.play.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One answer within an attempt, graded when it was given rather than at
 * submission. That is what makes submission idempotent: it aggregates stored
 * outcomes instead of re-grading.
 *
 * <p>Stores the outcome, never the correct answer.
 */
@Entity
@Table(name = "response")
public class Response {

    @Id
    private UUID id;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(columnDefinition = "text")
    private String given;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt = Instant.now();

    protected Response() {
    }

    public Response(UUID id, UUID attemptId, UUID questionId, String given, boolean correct) {
        this.id = id;
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.given = given;
        this.correct = correct;
    }

    public UUID getId() { return id; }
    public UUID getAttemptId() { return attemptId; }
    public UUID getQuestionId() { return questionId; }
    public String getGiven() { return given; }
    public boolean isCorrect() { return correct; }
    public Instant getAnsweredAt() { return answeredAt; }

    /** Replaces a previous answer to the same question. */
    public void replaceWith(String given, boolean correct) {
        this.given = given;
        this.correct = correct;
        this.answeredAt = Instant.now();
    }
}
