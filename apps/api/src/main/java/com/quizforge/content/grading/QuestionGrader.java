package com.quizforge.content.grading;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.Payload;

import java.util.Set;

public interface QuestionGrader {

    /** The types this grader handles. */
    Set<QuestionType> handles();

    /**
     * Grades one answer. A null, blank, or unparseable answer is incorrect,
     * never an error - a player fumbling input must not produce a 500.
     */
    GradingResult grade(Payload payload, String given);
}
