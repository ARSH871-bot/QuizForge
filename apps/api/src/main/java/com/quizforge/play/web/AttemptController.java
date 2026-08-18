package com.quizforge.play.web;

import com.quizforge.api.PlayApi;
import com.quizforge.api.model.AnswerFeedback;
import com.quizforge.api.model.AnswerRequest;
import com.quizforge.api.model.AttemptResult;
import com.quizforge.api.model.AttemptStarted;
import com.quizforge.api.model.AttemptState;
import com.quizforge.api.model.PlayableQuestion;
import com.quizforge.api.model.QuestionType;
import com.quizforge.content.QuestionAccess;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.play.app.AttemptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Playing a tournament. Implements {@link PlayApi}, generated from
 * {@code openapi.yaml}.
 *
 * <p>Identifiers arrive prefixed and are parsed by {@link TypeId}. The contract
 * additionally constrains their shape with a regular expression, so an
 * identifier of the wrong type is rejected before this class runs.
 */
@RestController
public class AttemptController implements PlayApi {

    private final AttemptService attempts;

    public AttemptController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @Override
    public ResponseEntity<AttemptStarted> startAttempt(String tournamentId,
                                                       String workspaceHeader) {
        UUID accountId = requireAccount();
        var attempt = attempts.start(TypeId.parse("trn", tournamentId), accountId);

        AttemptStarted body = new AttemptStarted(
                TypeId.render("att", attempt.getId()), attempt.getScoreDenominator());
        body.setExpiresAt(attempt.getExpiresAt() == null
                ? null
                : attempt.getExpiresAt().atOffset(ZoneOffset.UTC));

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Override
    public ResponseEntity<PlayableQuestion> getAttemptQuestion(String attemptId,
                                                               Integer position,
                                                               String workspaceHeader) {
        var question = attempts.question(TypeId.parse("att", attemptId), position,
                requireAccount());
        return ResponseEntity.ok(represent(question));
    }

    @Override
    public ResponseEntity<AnswerFeedback> answerAttemptQuestion(String attemptId,
                                                                Integer position,
                                                                AnswerRequest request,
                                                                String workspaceHeader) {
        var feedback = attempts.answer(TypeId.parse("att", attemptId), position,
                request.getAnswer(), requireAccount());

        return ResponseEntity.ok(new AnswerFeedback(
                feedback.position(), feedback.correct(), feedback.answered(),
                feedback.total(), feedback.hasNext()));
    }

    @Override
    public ResponseEntity<AttemptResult> submitAttempt(String attemptId, String workspaceHeader) {
        return ResponseEntity.ok(represent(
                attempts.submit(TypeId.parse("att", attemptId), requireAccount())));
    }

    @Override
    public ResponseEntity<AttemptResult> getAttemptResult(String attemptId,
                                                          String workspaceHeader) {
        return ResponseEntity.ok(represent(
                attempts.result(TypeId.parse("att", attemptId), requireAccount())));
    }

    /**
     * Renders {@code attemptId} with its {@code att_} prefix, so the identifier
     * returned here is the same string {@link #startAttempt} returned. The two
     * used to disagree, and a client could not use the value it had been given.
     */
    private AttemptResult represent(com.quizforge.play.app.AttemptResult result) {
        return new AttemptResult(
                TypeId.render("att", result.attemptId()),
                AttemptState.fromValue(result.state()),
                result.score(),
                result.outOf(),
                result.percentage());
    }

    /** Never carries the correct answer, for any question type. */
    private PlayableQuestion represent(QuestionAccess.PlayableQuestion question) {
        return new PlayableQuestion(
                TypeId.render("qst", question.questionId()),
                QuestionType.fromValue(question.type()),
                question.prompt(),
                question.options());
    }

    /**
     * Playing is something an <em>account</em> does. An API key authenticates a
     * workspace, not a person, so it cannot start or submit an attempt - there
     * would be nobody to attribute the score to.
     */
    private UUID requireAccount() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.accountId() == null) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED,
                    "playing requires a signed-in account, not an API key");
        }
        return principal.accountId();
    }
}
