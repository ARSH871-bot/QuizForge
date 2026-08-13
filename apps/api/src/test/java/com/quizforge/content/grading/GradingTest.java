package com.quizforge.content.grading;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.NumericPayload;
import com.quizforge.content.domain.payload.ShortTextPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradingTest {

    private final GraderRegistry graders = new GraderRegistry(
            List.of(new ChoiceGrader(), new NumericGrader(), new ShortTextGrader()));

    private static ChoicePayload capitals() {
        return new ChoicePayload(List.of(
                new ChoicePayload.Option("Paris", true),
                new ChoicePayload.Option("Lyon", false)));
    }

    @Test
    void singleChoiceAcceptsOnlyTheCorrectOption() {
        assertThat(graders.grade(QuestionType.SINGLE_CHOICE, capitals(), "Paris").correct()).isTrue();
        assertThat(graders.grade(QuestionType.SINGLE_CHOICE, capitals(), "Lyon").correct()).isFalse();
    }

    @Test
    void multiChoiceRequiresEveryCorrectOptionAndNoIncorrectOnes() {
        var payload = new ChoicePayload(List.of(
                new ChoicePayload.Option("a", true),
                new ChoicePayload.Option("b", true),
                new ChoicePayload.Option("c", false)));

        assertThat(graders.grade(QuestionType.MULTI_CHOICE, payload, "a,b").correct()).isTrue();
        assertThat(graders.grade(QuestionType.MULTI_CHOICE, payload, "a").correct()).isFalse();
        assertThat(graders.grade(QuestionType.MULTI_CHOICE, payload, "a,b,c").correct()).isFalse();
    }

    @Test
    void multiChoiceIgnoresOrderAndSpacing() {
        var payload = new ChoicePayload(List.of(
                new ChoicePayload.Option("a", true),
                new ChoicePayload.Option("b", true),
                new ChoicePayload.Option("c", false)));

        assertThat(graders.grade(QuestionType.MULTI_CHOICE, payload, " b , a ").correct()).isTrue();
    }

    @Test
    void numericAcceptsWithinTolerance() {
        var payload = new NumericPayload(10.0, 0.5);

        assertThat(graders.grade(QuestionType.NUMERIC, payload, "10.4").correct()).isTrue();
        assertThat(graders.grade(QuestionType.NUMERIC, payload, "10.6").correct()).isFalse();
        assertThat(graders.grade(QuestionType.NUMERIC, payload, "not a number").correct()).isFalse();
    }

    @Test
    void shortTextNormalisesCaseAccentsAndWhitespace() {
        var payload = new ShortTextPayload(List.of("Cafe\u0301"), true);

        assertThat(graders.grade(QuestionType.SHORT_TEXT, payload, "  cafe\u0301  ").correct()).isTrue();
        assertThat(graders.grade(QuestionType.SHORT_TEXT, payload, "coffee").correct()).isFalse();
    }

    @Test
    void gradingResultNeverCarriesTheCorrectAnswerWhenWrong() {
        var result = graders.grade(QuestionType.SINGLE_CHOICE, capitals(), "Lyon");

        assertThat(result.correct()).isFalse();
        assertThat(result.toString()).doesNotContain("Paris");
    }

    @Test
    void anEmptyAnswerIsIncorrectRatherThanAnError() {
        var payload = new NumericPayload(10.0, 0.0);

        assertThat(graders.grade(QuestionType.NUMERIC, payload, null).correct()).isFalse();
        assertThat(graders.grade(QuestionType.NUMERIC, payload, "").correct()).isFalse();
        assertThat(graders.grade(QuestionType.SINGLE_CHOICE, capitals(), null).correct()).isFalse();
        assertThat(graders.grade(QuestionType.SHORT_TEXT,
                new ShortTextPayload(List.of("x"), true), null).correct()).isFalse();
    }

    @Test
    void trueFalseIsGradedLikeSingleChoice() {
        var payload = new ChoicePayload(List.of(
                new ChoicePayload.Option("True", false),
                new ChoicePayload.Option("False", true)));

        assertThat(graders.grade(QuestionType.TRUE_FALSE, payload, "False").correct()).isTrue();
        assertThat(graders.grade(QuestionType.TRUE_FALSE, payload, "True").correct()).isFalse();
    }
}
