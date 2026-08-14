package com.quizforge.tournament;

/**
 * How multiple attempts collapse into one standing. Per ADR 0003, defaulting to
 * FIRST with maxAttempts of 1 preserves single-attempt semantics while making
 * practice modes a configuration change rather than a migration.
 */
public enum ScoringPolicy {
    /** The highest score across attempts. */
    BEST,
    /** The first attempt only; later ones do not count. */
    FIRST,
    /** The most recent attempt. */
    LAST,
    /** The mean across all graded attempts. */
    AVERAGE
}
