package com.quizforge.play.domain;

/**
 * The lifecycle of one player's run at a tournament.
 *
 * <pre>
 *   STARTED ──submit()──▶ GRADED
 *      │
 *      └────expire()────▶ EXPIRED
 * </pre>
 *
 * <p>Both terminal states carry a score: an expired attempt is graded on
 * whatever was answered, so an abandoned attempt still settles the leaderboard
 * rather than hanging there forever.
 *
 * <p>{@code SUBMITTED} is deliberately absent. Grading happens per response as
 * the player advances, so submission and grading are one transition — there is
 * no window in which an attempt is submitted but not yet scored.
 */
public enum AttemptState {

    /** Accepting answers. */
    STARTED,

    /** Submitted by the player and scored. Terminal. */
    GRADED,

    /** Ran out of time and was scored on what existed. Terminal. */
    EXPIRED;

    public boolean isTerminal() {
        return this != STARTED;
    }
}
