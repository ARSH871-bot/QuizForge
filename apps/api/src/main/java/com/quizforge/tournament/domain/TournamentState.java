package com.quizforge.tournament.domain;

/**
 * Derived from the clock, never stored. A stored state needs a scheduled job to
 * transition it, and can disagree with the calendar whenever that job is late.
 */
public enum TournamentState {
    SCHEDULED,
    OPEN,
    CLOSED
}
