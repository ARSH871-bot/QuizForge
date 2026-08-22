package com.quizforge.content.web;

import com.quizforge.api.model.ChoiceOption;
import com.quizforge.api.model.ChoicePayload;
import com.quizforge.api.model.NumericPayload;
import com.quizforge.api.model.QuestionPayload;
import com.quizforge.api.model.ShortTextPayload;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.platform.error.ApiException;

import java.util.List;

/**
 * Translates between the contract's payload union and the domain's payloads.
 *
 * <p>The two are deliberately different types. The contract's union carries a
 * {@code kind} discriminator so a typed client can narrow exhaustively; the
 * stored payload does not, and must not — every row written since M2 lacks that
 * field, and {@code QuestionType.parsePayload} deserialises with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled, so adding it to the domain record
 * would make existing questions unreadable.
 *
 * <p>Keeping the discriminator at the API boundary gets the client-side
 * narrowing without touching a single stored byte. This class is the seam.
 */
final class PayloadMapper {

    private PayloadMapper() {
    }

    /** Contract shape to domain shape. */
    static Payload toDomain(QuestionPayload payload) {
        if (payload == null) {
            throw ApiException.invalid("a question needs a payload");
        }

        return switch (payload) {
            case ChoicePayload choice -> new com.quizforge.content.domain.payload.ChoicePayload(
                    choice.getOptions() == null ? List.of() : choice.getOptions().stream()
                            .map(o -> new com.quizforge.content.domain.payload.ChoicePayload.Option(
                                    o.getText(), Boolean.TRUE.equals(o.getCorrect())))
                            .toList());

            case NumericPayload numeric -> new com.quizforge.content.domain.payload.NumericPayload(
                    numeric.getValue(), numeric.getTolerance());

            case ShortTextPayload text ->
                    new com.quizforge.content.domain.payload.ShortTextPayload(
                            text.getAccepted() == null ? List.of() : text.getAccepted(),
                            Boolean.TRUE.equals(text.getIgnoreCase()));

            // Unreachable while the union has three members. Present because a
            // fourth would otherwise be a silent null rather than a loud error.
            default -> throw ApiException.invalid("unrecognised payload shape");
        };
    }

    /** Domain shape to contract shape, stamping the discriminator. */
    static QuestionPayload toApi(Payload payload) {
        return switch (payload) {
            case com.quizforge.content.domain.payload.ChoicePayload choice -> {
                ChoicePayload body = new ChoicePayload(
                        ChoicePayload.KindEnum.CHOICE,
                        choice.options().stream()
                                .map(o -> new ChoiceOption(o.text(), o.correct()))
                                .toList());
                yield body;
            }

            case com.quizforge.content.domain.payload.NumericPayload numeric ->
                    new NumericPayload(NumericPayload.KindEnum.NUMERIC,
                            numeric.value(), numeric.tolerance());

            case com.quizforge.content.domain.payload.ShortTextPayload text ->
                    new ShortTextPayload(ShortTextPayload.KindEnum.SHORT_TEXT,
                            text.accepted(), text.ignoreCase());

            default -> throw new IllegalStateException(
                    "no contract shape for " + payload.getClass().getSimpleName());
        };
    }
}
