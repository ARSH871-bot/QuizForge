package com.quizforge.content.domain.payload;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.platform.error.ApiException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accepted answers are compared after normalisation, never by edit distance.
 * Fuzzy matching produces disputes with no principled threshold; an author who
 * wants leniency supplies more accepted answers.
 */
public record ShortTextPayload(List<String> accepted, boolean ignoreCase) implements Payload {

    /** Defensively copies the accepted answers - see {@link ChoicePayload}. */
    public ShortTextPayload {
        accepted = accepted == null ? null : Collections.unmodifiableList(new ArrayList<>(accepted));
    }

    @Override
    public void validate(QuestionType type) {
        if (type != QuestionType.SHORT_TEXT) {
            throw ApiException.invalid(type + " does not use a short text payload");
        }
        if (accepted == null || accepted.isEmpty()) {
            throw ApiException.invalid("a short text question needs at least one accepted answer");
        }
        if (accepted.stream().anyMatch(a -> a == null || a.isBlank())) {
            throw ApiException.invalid("accepted answers cannot be blank");
        }
    }
}
