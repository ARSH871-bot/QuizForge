package com.quizforge.content.domain.payload;

import com.quizforge.content.domain.QuestionType;

/** Type-specific question data. Implementations are immutable records. */
public interface Payload {

    /**
     * Rejects a payload that cannot be graded - no correct answer, too few
     * options, a negative tolerance. Called on write so a malformed question
     * can never reach the database, since the JSONB column cannot enforce
     * shape itself.
     */
    void validate(QuestionType type);
}
