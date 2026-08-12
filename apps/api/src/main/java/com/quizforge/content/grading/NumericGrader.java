package com.quizforge.content.grading;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.NumericPayload;
import com.quizforge.content.domain.payload.Payload;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class NumericGrader implements QuestionGrader {

    @Override
    public Set<QuestionType> handles() {
        return Set.of(QuestionType.NUMERIC);
    }

    @Override
    public GradingResult grade(Payload payload, String given) {
        if (!(payload instanceof NumericPayload numeric) || given == null || given.isBlank()) {
            return GradingResult.of(false);
        }

        double answer;
        try {
            answer = Double.parseDouble(given.trim());
        } catch (NumberFormatException e) {
            // A player typing "about ten" is wrong, not an error.
            return GradingResult.of(false);
        }

        double tolerance = numeric.tolerance() == null ? 0.0 : numeric.tolerance();
        return Math.abs(answer - numeric.value()) <= tolerance
                ? GradingResult.of(true)
                : GradingResult.of(false);
    }
}
