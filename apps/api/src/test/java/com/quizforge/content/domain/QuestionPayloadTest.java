package com.quizforge.content.domain;

import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.NumericPayload;
import com.quizforge.content.domain.payload.ShortTextPayload;
import com.quizforge.platform.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionPayloadTest {

    @Test
    void singleChoiceRequiresExactlyOneCorrectOption() {
        var valid = new ChoicePayload(List.of(
                new ChoicePayload.Option("a", true), new ChoicePayload.Option("b", false)));
        valid.validate(QuestionType.SINGLE_CHOICE);

        var twoCorrect = new ChoicePayload(List.of(
                new ChoicePayload.Option("a", true), new ChoicePayload.Option("b", true)));

        assertThatThrownBy(() -> twoCorrect.validate(QuestionType.SINGLE_CHOICE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void multiChoiceRequiresAtLeastOneCorrectOption() {
        var noneCorrect = new ChoicePayload(List.of(
                new ChoicePayload.Option("a", false), new ChoicePayload.Option("b", false)));

        assertThatThrownBy(() -> noneCorrect.validate(QuestionType.MULTI_CHOICE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void choiceRequiresAtLeastTwoOptions() {
        var single = new ChoicePayload(List.of(new ChoicePayload.Option("a", true)));

        assertThatThrownBy(() -> single.validate(QuestionType.SINGLE_CHOICE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void trueFalseRequiresExactlyTwoOptions() {
        var three = new ChoicePayload(List.of(
                new ChoicePayload.Option("True", true),
                new ChoicePayload.Option("False", false),
                new ChoicePayload.Option("Maybe", false)));

        assertThatThrownBy(() -> three.validate(QuestionType.TRUE_FALSE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly two");
    }

    @Test
    void numericRejectsNegativeTolerance() {
        assertThatThrownBy(() -> new NumericPayload(10.0, -1.0).validate(QuestionType.NUMERIC))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    void shortTextRequiresAtLeastOneAcceptedAnswer() {
        assertThatThrownBy(() ->
                new ShortTextPayload(List.of(), true).validate(QuestionType.SHORT_TEXT))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void payloadsRoundTripThroughJson() {
        var payload = new ChoicePayload(List.of(
                new ChoicePayload.Option("Paris", true),
                new ChoicePayload.Option("Lyon", false)));

        String json = QuestionType.SINGLE_CHOICE.writePayload(payload);
        var parsed = (ChoicePayload) QuestionType.SINGLE_CHOICE.parsePayload(json);

        assertThat(parsed.options()).hasSize(2);
        assertThat(parsed.options().get(0).text()).isEqualTo("Paris");
        assertThat(parsed.options().get(0).correct()).isTrue();
    }

    @Test
    void payloadsAreImmutableAfterConstruction() {
        var mutable = new java.util.ArrayList<>(List.of(
                new ChoicePayload.Option("a", true), new ChoicePayload.Option("b", false)));
        var payload = new ChoicePayload(mutable);

        mutable.add(new ChoicePayload.Option("smuggled", true));

        assertThat(payload.options())
                .as("a payload must not change after the caller mutates the list it was given")
                .hasSize(2);
        assertThatThrownBy(() -> payload.options().add(new ChoicePayload.Option("c", true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAPayloadOfTheWrongShape() {
        assertThatThrownBy(() -> QuestionType.NUMERIC.parsePayload("{\"options\":[]}"))
                .isInstanceOf(ApiException.class);
    }
}
