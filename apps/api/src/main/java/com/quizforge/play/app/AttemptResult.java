package com.quizforge.play.app;

import java.util.UUID;

/** The outcome of a closed attempt. */
public record AttemptResult(
        UUID attemptId,
        String state,
        int score,
        int outOf,
        double percentage) {
}
