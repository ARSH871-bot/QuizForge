package com.quizforge.content.grading;

import com.quizforge.content.app.ContentHash;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.Payload;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ChoiceGrader implements QuestionGrader {

    @Override
    public Set<QuestionType> handles() {
        return Set.of(QuestionType.SINGLE_CHOICE,
                QuestionType.MULTI_CHOICE,
                QuestionType.TRUE_FALSE);
    }

    /**
     * Compares normalised option text as a set, so order and spacing do not
     * matter. Set equality is deliberate for multiple-answer questions:
     * selecting every correct option plus an incorrect one is wrong, not
     * partially right.
     */
    @Override
    public GradingResult grade(Payload payload, String given) {
        if (!(payload instanceof ChoicePayload choice) || given == null || given.isBlank()) {
            return GradingResult.of(false);
        }

        Set<String> expected = choice.options().stream()
                .filter(ChoicePayload.Option::correct)
                .map(o -> ContentHash.normalise(o.text()))
                .collect(Collectors.toSet());

        Set<String> selected = Arrays.stream(given.split(","))
                .map(ContentHash::normalise)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return selected.equals(expected) ? GradingResult.of(true) : GradingResult.of(false);
    }
}
