package com.quizforge.content.grading;

import com.quizforge.content.app.ContentHash;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.content.domain.payload.ShortTextPayload;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Set;

@Component
public class ShortTextGrader implements QuestionGrader {

    @Override
    public Set<QuestionType> handles() {
        return Set.of(QuestionType.SHORT_TEXT);
    }

    /**
     * Normalises both sides and compares exactly. No edit distance: fuzzy
     * matching produces disputes with no principled threshold, and an author
     * who wants leniency supplies more accepted answers.
     */
    @Override
    public GradingResult grade(Payload payload, String given) {
        if (!(payload instanceof ShortTextPayload text) || given == null || given.isBlank()) {
            return GradingResult.of(false);
        }

        if (text.ignoreCase()) {
            String candidate = ContentHash.normalise(given);
            return text.accepted().stream().map(ContentHash::normalise)
                    .anyMatch(candidate::equals)
                    ? GradingResult.of(true) : GradingResult.of(false);
        }

        String candidate = compose(given);
        return text.accepted().stream().map(ShortTextGrader::compose)
                .anyMatch(candidate::equals)
                ? GradingResult.of(true) : GradingResult.of(false);
    }

    /** NFKC and whitespace only, preserving case for a case-sensitive question. */
    private static String compose(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
