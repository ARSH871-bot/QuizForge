package com.quizforge.content.domain.payload;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.platform.error.ApiException;

/** {@code tolerance} is absolute: an answer counts if |given - value| <= tolerance. */
public record NumericPayload(Double value, Double tolerance) implements Payload {

    @Override
    public void validate(QuestionType type) {
        if (type != QuestionType.NUMERIC) {
            throw ApiException.invalid(type + " does not use a numeric payload");
        }
        if (value == null) {
            throw ApiException.invalid("a numeric question needs a value");
        }
        if (tolerance == null || tolerance < 0) {
            throw ApiException.invalid("tolerance must be zero or greater");
        }
    }
}
