package com.quizforge.content.grading;

/**
 * The outcome of grading one answer. Deliberately carries no reference to the
 * correct answer: this crosses the network to the player, and revealing the
 * answer is a separate step taken only after an attempt is closed.
 *
 * <p>A single {@code of} factory rather than named {@code correct()} and
 * {@code incorrect()} ones - the record component already generates an
 * accessor named {@code correct()}, which a static factory cannot shadow.
 */
public record GradingResult(boolean correct) {

    public static GradingResult of(boolean correct) {
        return new GradingResult(correct);
    }
}
