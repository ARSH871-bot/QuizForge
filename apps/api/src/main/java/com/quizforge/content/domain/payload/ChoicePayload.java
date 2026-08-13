package com.quizforge.content.domain.payload;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.platform.error.ApiException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ChoicePayload(List<Option> options) implements Payload {

    public record Option(String text, boolean correct) {
    }

    /**
     * Defensively copies the option list. Without this the record is immutable
     * in name only - the caller keeps a reference and can mutate a question's
     * answers after it has been validated.
     *
     * <p>A plain copy rather than {@code List.copyOf}, which throws on a null
     * element and would replace {@link #validate}'s specific message with an
     * opaque NPE.
     */
    public ChoicePayload {
        options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
    }

    @Override
    public void validate(QuestionType type) {
        if (options == null || options.size() < 2) {
            throw ApiException.invalid("a choice question needs at least two options");
        }
        if (options.stream().anyMatch(o -> o.text() == null || o.text().isBlank())) {
            throw ApiException.invalid("every option needs text");
        }

        long correct = options.stream().filter(Option::correct).count();

        switch (type) {
            case SINGLE_CHOICE, TRUE_FALSE -> {
                if (correct != 1) {
                    throw ApiException.invalid(
                            "a " + type + " question needs exactly one correct option");
                }
            }
            case MULTI_CHOICE -> {
                if (correct < 1) {
                    throw ApiException.invalid(
                            "a multiple-answer question needs at least one correct option");
                }
            }
            default -> throw ApiException.invalid(type + " does not use a choice payload");
        }

        if (type == QuestionType.TRUE_FALSE && options.size() != 2) {
            throw ApiException.invalid("a true/false question needs exactly two options");
        }
    }
}
