package com.quizforge.play.app;

/**
 * What a player learns after answering one question.
 *
 * <p>Deliberately carries no correct answer, even when the answer was wrong.
 * Revealing it is a separate step taken only once the attempt is closed —
 * otherwise a player can extract the whole answer key by submitting one wrong
 * answer per question.
 */
public record AnswerFeedback(
        int position,
        boolean correct,
        int answered,
        int total,
        boolean hasNext) {
}
