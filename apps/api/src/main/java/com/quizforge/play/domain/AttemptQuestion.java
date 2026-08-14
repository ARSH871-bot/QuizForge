package com.quizforge.play.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One question frozen into an attempt, with the option order that player saw.
 *
 * <p>Storing the order is what lets a graded attempt be replayed exactly while
 * two players still receive different orders. Shuffling at import would have
 * made the content hash unstable; this is where shuffling belongs.
 */
@Entity
@Table(name = "attempt_question")
@IdClass(AttemptQuestion.Key.class)
public class AttemptQuestion {

    /** Composite key: an attempt plus a position within it. */
    public static class Key implements Serializable {
        private UUID attemptId;
        private int position;

        public Key() {
        }

        public Key(UUID attemptId, int position) {
            this.attemptId = attemptId;
            this.position = position;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return position == key.position && Objects.equals(attemptId, key.attemptId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attemptId, position);
        }
    }

    @Id
    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Id
    @Column(nullable = false)
    private int position;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "option_order", nullable = false)
    private String optionOrder;

    protected AttemptQuestion() {
    }

    public AttemptQuestion(UUID attemptId, int position, UUID questionId, String optionOrder) {
        this.attemptId = attemptId;
        this.position = position;
        this.questionId = questionId;
        this.optionOrder = optionOrder;
    }

    public UUID getAttemptId() { return attemptId; }
    public int getPosition() { return position; }
    public UUID getQuestionId() { return questionId; }
    public String getOptionOrder() { return optionOrder; }
}
