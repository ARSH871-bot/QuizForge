package com.quizforge.play.web;

import com.quizforge.content.QuestionAccess;
import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.play.app.AnswerFeedback;
import com.quizforge.play.app.AttemptResult;
import com.quizforge.play.app.AttemptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Playing a tournament.
 *
 * <p>Identifiers arrive prefixed (`trn_…`, `att_…`) and are parsed by
 * {@link TypeId}, which rejects an id of the wrong type outright — passing a
 * tournament id where an attempt id belongs is a 400 rather than a confusing
 * 404 later.
 */
@RestController
@RequestMapping("/v1")
public class AttemptController {

    /** The player's answer. A record so the field name is part of the contract. */
    public record AnswerRequest(@NotBlank String answer) {
    }

    private final AttemptService attempts;

    public AttemptController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @PostMapping("/tournaments/{tournamentId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public AttemptStarted start(@PathVariable String tournamentId,
                                @AuthenticationPrincipal Principal principal) {
        UUID accountId = requireAccount(principal);
        var attempt = attempts.start(TypeId.parse("trn", tournamentId), accountId);

        return new AttemptStarted(TypeId.render("att", attempt.getId()),
                attempt.getScoreDenominator(), attempt.getExpiresAt());
    }

    @GetMapping("/attempts/{attemptId}/questions/{position}")
    public QuestionAccess.PlayableQuestion question(@PathVariable String attemptId,
                                                    @PathVariable int position,
                                                    @AuthenticationPrincipal Principal principal) {
        return attempts.question(TypeId.parse("att", attemptId), position,
                requireAccount(principal));
    }

    @PostMapping("/attempts/{attemptId}/questions/{position}/answer")
    public AnswerFeedback answer(@PathVariable String attemptId,
                                 @PathVariable int position,
                                 @Valid @RequestBody AnswerRequest request,
                                 @AuthenticationPrincipal Principal principal) {
        return attempts.answer(TypeId.parse("att", attemptId), position,
                request.answer(), requireAccount(principal));
    }

    @PostMapping("/attempts/{attemptId}/submit")
    public AttemptResult submit(@PathVariable String attemptId,
                                @AuthenticationPrincipal Principal principal) {
        return attempts.submit(TypeId.parse("att", attemptId), requireAccount(principal));
    }

    @GetMapping("/attempts/{attemptId}")
    public AttemptResult result(@PathVariable String attemptId,
                                @AuthenticationPrincipal Principal principal) {
        return attempts.result(TypeId.parse("att", attemptId), requireAccount(principal));
    }

    /** What a player needs to begin. Carries no question content. */
    public record AttemptStarted(String id, int questions, java.time.Instant expiresAt) {
    }

    /**
     * Playing is something an <em>account</em> does. An API key authenticates a
     * workspace, not a person, so it cannot start or submit an attempt — there
     * would be nobody to attribute the score to.
     */
    private UUID requireAccount(Principal principal) {
        if (principal == null || principal.accountId() == null) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED,
                    "playing requires a signed-in account, not an API key");
        }
        return principal.accountId();
    }
}
