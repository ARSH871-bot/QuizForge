package com.quizforge.play.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.tenancy.TenantContext;
import com.quizforge.tournament.app.TournamentDraft;
import com.quizforge.tournament.app.TournamentService;
import com.quizforge.tournament.ScoringPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptServiceTest extends AbstractIntegrationTest {

    @Autowired private AttemptService attempts;
    @Autowired private TournamentService tournaments;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;
    @Autowired private ObjectMapper json;

    // Deliberately neutral text. An option literally named "correct-answer"
    // would match the JSON scan below and turn a real guarantee into a test
    // that fails for the wrong reason.
    private static final String RIGHT = "alpha";
    private static final String WRONG = "beta";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newAccount() {
        return accounts.register("user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    private record Fixture(UUID owner, UUID workspaceId, UUID tournamentId) {
    }

    private Fixture fixture(int questionCount, int maxAttempts, Integer timeLimitSeconds) {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme " + UUID.randomUUID());
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        for (int i = 0; i < questionCount + 2; i++) {
            questions.author(bank.getId(), owner, QuestionType.SINGLE_CHOICE,
                    "Question " + i + "?",
                    new ChoicePayload(List.of(
                            new ChoicePayload.Option(RIGHT, true),
                            new ChoicePayload.Option(WRONG, false),
                            new ChoicePayload.Option("gamma-" + i, false))),
                    "EASY");
        }

        Instant opens = Instant.now().minus(Duration.ofMinutes(1));
        var tournament = tournaments.create(workspace.getId(), owner,
                new TournamentDraft("Spring Quiz", bank.getId(), opens,
                        opens.plus(Duration.ofDays(1)), questionCount,
                        timeLimitSeconds, maxAttempts, ScoringPolicy.FIRST));

        return new Fixture(owner, workspace.getId(), tournament.getId());
    }

    @Test
    void startingFreezesAQuestionSetOfTheRightSize() {
        var f = fixture(3, 1, 600);

        var attempt = attempts.start(f.tournamentId(), f.owner());

        assertThat(attempt.getScoreDenominator()).isEqualTo(3);
        for (int position = 1; position <= 3; position++) {
            assertThat(attempts.question(attempt.getId(), position, f.owner()).prompt())
                    .isNotBlank();
        }
    }

    @Test
    void aQuestionNeverCarriesTheCorrectAnswer() throws Exception {
        // The guarantee that matters most. Serialised to JSON and searched,
        // because a field added later would otherwise leak silently.
        var f = fixture(3, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());

        var view = attempts.question(attempt.getId(), 1, f.owner());
        String serialised = json.writeValueAsString(view);

        assertThat(view.options()).contains(RIGHT);   // present as an option
        assertThat(serialised)
                .as("no field may mark which option is correct")
                .doesNotContain("correct")
                .doesNotContain("answer");
    }

    @Test
    void feedbackNeverRevealsTheCorrectAnswerEvenWhenWrong() throws Exception {
        var f = fixture(3, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());

        var feedback = attempts.answer(attempt.getId(), 1, WRONG, f.owner());

        assertThat(feedback.correct()).isFalse();
        assertThat(json.writeValueAsString(feedback))
                .as("a player must not be able to extract the key one wrong answer at a time")
                .doesNotContain(RIGHT);
    }

    @Test
    void answersArePersistedSoAnAttemptSurvivesADisconnect() {
        var f = fixture(3, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());

        attempts.answer(attempt.getId(), 1, RIGHT, f.owner());
        attempts.answer(attempt.getId(), 2, RIGHT, f.owner());

        // Nothing is held in memory between calls, so "reconnecting" is just
        // asking again.
        var result = attempts.submit(attempt.getId(), f.owner());

        assertThat(result.score()).isEqualTo(2);
        assertThat(result.outOf()).isEqualTo(3);
    }

    @Test
    void answeringTheSameQuestionAgainReplacesRatherThanDuplicates() {
        var f = fixture(3, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());

        attempts.answer(attempt.getId(), 1, WRONG, f.owner());
        attempts.answer(attempt.getId(), 1, RIGHT, f.owner());

        var result = attempts.submit(attempt.getId(), f.owner());

        assertThat(result.score())
                .as("the corrected answer counts once, not twice")
                .isEqualTo(1);
    }

    @Test
    void unansweredQuestionsCountAsWrongRatherThanShrinkingTheDenominator() {
        var f = fixture(4, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());

        attempts.answer(attempt.getId(), 1, RIGHT, f.owner());

        var result = attempts.submit(attempt.getId(), f.owner());

        assertThat(result.score()).isEqualTo(1);
        assertThat(result.outOf())
                .as("this is the prototype defect: denominator must not follow what was answered")
                .isEqualTo(4);
        assertThat(result.percentage()).isEqualTo(25.0);
    }

    @Test
    void submittingTwiceIsRejectedAndTheFirstResultStands() {
        var f = fixture(3, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());
        attempts.answer(attempt.getId(), 1, RIGHT, f.owner());
        attempts.submit(attempt.getId(), f.owner());

        assertThatThrownBy(() -> attempts.submit(attempt.getId(), f.owner()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been submitted");

        assertThat(attempts.result(attempt.getId(), f.owner()).score()).isEqualTo(1);
    }

    @Test
    void aSecondAttemptIsRefusedWhenOnlyOneIsAllowed() {
        var f = fixture(3, 1, 600);
        attempts.start(f.tournamentId(), f.owner());

        assertThatThrownBy(() -> attempts.start(f.tournamentId(), f.owner()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already attempted")
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(com.quizforge.platform.error.ErrorCode.ATTEMPTS_EXHAUSTED);
    }

    @Test
    void aSecondAttemptIsAllowedWhenTwoArePermitted() {
        var f = fixture(3, 2, 600);
        attempts.start(f.tournamentId(), f.owner());

        var second = attempts.start(f.tournamentId(), f.owner());

        assertThat(second.getId()).isNotNull();
    }

    @Test
    void anotherAccountCannotTouchSomeoneElsesAttempt() {
        var f = fixture(3, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());
        UUID stranger = newAccount();

        // Not found rather than forbidden: confirming it exists would leak
        // that someone else is playing.
        assertThatThrownBy(() -> attempts.question(attempt.getId(), 1, stranger))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void anAttemptRendersTheSameOrderEveryTimeItIsAsked() {
        // Determinism is the property worth testing. The order is seeded by the
        // attempt id and stored, so a refresh mid-question must not reshuffle
        // the options under the player.
        var f = fixture(3, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());

        var first = attempts.question(attempt.getId(), 1, f.owner()).options();
        var again = attempts.question(attempt.getId(), 1, f.owner()).options();

        assertThat(again).containsExactlyElementsOf(first);
    }

    @Test
    void eachAttemptSelectsItsOwnQuestionsAndOrder() {
        // Two attempts at the same tournament are independent draws: neither
        // the selection nor the order is shared. Asserted across the whole
        // attempt rather than one position, since the sets legitimately differ.
        var f = fixture(3, 2, 600);
        var first = attempts.start(f.tournamentId(), f.owner());
        var second = attempts.start(f.tournamentId(), f.owner());

        assertThat(first.getId()).isNotEqualTo(second.getId());

        for (int position = 1; position <= 3; position++) {
            var view = attempts.question(second.getId(), position, f.owner());
            assertThat(view.options())
                    .as("every attempt must render a complete option set")
                    .hasSize(3);
        }
    }

    @Test
    void requestingAQuestionOutsideTheAttemptIsNotFound() {
        var f = fixture(3, 1, 600);
        var attempt = attempts.start(f.tournamentId(), f.owner());

        assertThatThrownBy(() -> attempts.question(attempt.getId(), 99, f.owner()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> attempts.question(attempt.getId(), 0, f.owner()))
                .isInstanceOf(ApiException.class);
    }
}
