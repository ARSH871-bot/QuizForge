package com.quizforge.play.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.content.QuestionAccess;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import com.quizforge.play.domain.Attempt;
import com.quizforge.play.domain.AttemptState;
import com.quizforge.play.domain.AttemptQuestion;
import com.quizforge.play.domain.Response;
import com.quizforge.play.repo.AttemptQuestionRepository;
import com.quizforge.play.repo.AttemptRepository;
import com.quizforge.play.AttemptGraded;
import com.quizforge.play.repo.ResponseRepository;
import com.quizforge.tournament.TournamentAccess;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
public class AttemptService {

    private final AttemptRepository attempts;
    private final AttemptQuestionRepository attemptQuestions;
    private final ResponseRepository responses;
    private final TournamentAccess tournaments;
    private final QuestionAccess questions;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public AttemptService(AttemptRepository attempts, AttemptQuestionRepository attemptQuestions,
                          ResponseRepository responses, TournamentAccess tournaments,
                          QuestionAccess questions, ObjectMapper objectMapper,
                          ApplicationEventPublisher events) {
        this.attempts = attempts;
        this.attemptQuestions = attemptQuestions;
        this.responses = responses;
        this.tournaments = tournaments;
        this.questions = questions;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    /**
     * Starts an attempt, freezing its question set and per-question option
     * order. Everything the player will see is decided here, so a graded
     * attempt can be reconstructed exactly.
     */
    @Transactional
    public Attempt start(UUID tournamentId, UUID accountId) {
        // The open/closed decision belongs to the tournament module, not here.
        TournamentAccess.PlayableTournament tournament = tournaments.requireOpen(tournamentId);
        Instant now = Instant.now();

        long used = attempts.countByTournamentIdAndAccountId(tournamentId, accountId);
        if (used >= tournament.maxAttempts()) {
            throw ApiException.invalid(tournament.maxAttempts() == 1
                    ? "you have already attempted this tournament"
                    : "you have used all " + tournament.maxAttempts() + " attempts");
        }

        UUID attemptId = UuidV7.generate();
        Instant expiresAt = tournament.timeLimitSeconds() == null
                ? null
                : now.plus(Duration.ofSeconds(tournament.timeLimitSeconds()));

        Attempt attempt = attempts.save(new Attempt(attemptId, tournamentId,
                tournament.workspaceId(), accountId, tournament.questionCount(),
                now, expiresAt));

        freezeQuestions(attempt, tournament);
        return attempt;
    }

    /**
     * Every attempt against a tournament, newest first.
     *
     * <p>Scoped by Row-Level Security rather than by an explicit predicate, like
     * every other read here: a tournament in another workspace yields nothing.
     */
    @Transactional(readOnly = true)
    public List<Attempt> forTournament(UUID tournamentId) {
        return attempts.findByTournamentIdOrderByStartedAtDesc(tournamentId);
    }

    /** Renders one question, in the order this attempt froze. Never carries the answer. */
    @Transactional(readOnly = true)
    public QuestionAccess.PlayableQuestion question(UUID attemptId, int position, UUID accountId) {
        Attempt attempt = requireOwned(attemptId, accountId);
        AttemptQuestion frozen = requireQuestionAt(attempt, position);

        return questions.playable(frozen.getQuestionId(), readOrder(frozen));
    }

    /**
     * Records and grades one answer. Grading here rather than at submission is
     * what makes submission idempotent: it aggregates stored outcomes instead
     * of re-grading.
     */
    @Transactional
    public AnswerFeedback answer(UUID attemptId, int position, String given, UUID accountId) {
        Attempt attempt = requireOwned(attemptId, accountId);
        attempt.requireAnswerable(Instant.now());

        AttemptQuestion frozen = requireQuestionAt(attempt, position);
        boolean correct = questions.grade(frozen.getQuestionId(), given);

        responses.findByAttemptIdAndQuestionId(attemptId, frozen.getQuestionId())
                .ifPresentOrElse(
                        existing -> {
                            existing.replaceWith(given, correct);
                            responses.save(existing);
                        },
                        () -> responses.save(new Response(UuidV7.generate(), attemptId,
                                frozen.getQuestionId(), given, correct)));

        int answered = responses.findByAttemptId(attemptId).size();

        // Deliberately no correct answer, even when wrong. Revealing it is a
        // separate step after the attempt closes.
        return new AnswerFeedback(position, correct, answered,
                attempt.getScoreDenominator(),
                position < attempt.getScoreDenominator());
    }

    /** Submits and grades. Aggregates stored outcomes, so re-submitting is rejected cleanly. */
    @Transactional
    public AttemptResult submit(UUID attemptId, UUID accountId) {
        Attempt attempt = requireOwned(attemptId, accountId);

        int correct = (int) responses.countByAttemptIdAndCorrectIsTrue(attemptId);
        attempt.submit(correct, Instant.now());
        attempts.save(attempt);

        // Published inside the transaction, so a rollback takes the event with
        // it. Consumers must still be idempotent - delivery is at-least-once.
        events.publishEvent(new AttemptGraded(attempt.getId(), attempt.getTournamentId(),
                attempt.getWorkspaceId(), attempt.getAccountId(),
                attempt.getScoreNumerator(), attempt.getScoreDenominator(),
                attempt.getGradedAt()));

        return result(attempt);
    }

    /**
     * Closes one overdue attempt, scoring whatever was answered.
     *
     * <p>Separate transaction per attempt so a single bad row cannot abort the
     * whole sweep, and returns quietly if another instance closed it first.
     */
    @Transactional
    public boolean expireIfOverdue(UUID attemptId) {
        Attempt attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getState().isTerminal()
                || !attempt.isExpired(Instant.now())) {
            return false;
        }

        int correct = (int) responses.countByAttemptIdAndCorrectIsTrue(attemptId);
        attempt.expire(correct, Instant.now());
        attempts.save(attempt);

        // Expired attempts still settle the leaderboard. Without this an
        // abandoned attempt would leave standings permanently incomplete.
        events.publishEvent(new AttemptGraded(attempt.getId(), attempt.getTournamentId(),
                attempt.getWorkspaceId(), attempt.getAccountId(),
                attempt.getScoreNumerator(), attempt.getScoreDenominator(),
                attempt.getGradedAt()));

        return true;
    }

    /** Ids of attempts past their limit but still open. */
    @Transactional(readOnly = true)
    public List<UUID> overdueAttemptIds() {
        return attempts.findByStateAndExpiresAtBefore(AttemptState.STARTED, Instant.now())
                .stream().map(Attempt::getId).toList();
    }

    @Transactional(readOnly = true)
    public AttemptResult result(UUID attemptId, UUID accountId) {
        return result(requireOwned(attemptId, accountId));
    }

    private AttemptResult result(Attempt attempt) {
        return new AttemptResult(attempt.getId(), attempt.getState().name(),
                attempt.getScoreNumerator(), attempt.getScoreDenominator(),
                attempt.percentage());
    }

    /**
     * Freezes the question set. The seed is derived from the attempt id, so the
     * selection and shuffle are reproducible for this attempt and different
     * between players.
     */
    private void freezeQuestions(Attempt attempt, TournamentAccess.PlayableTournament tournament) {
        long seed = attempt.getId().getMostSignificantBits()
                ^ attempt.getId().getLeastSignificantBits();

        List<UUID> selected = questions.selectQuestions(
                tournament.bankId(), tournament.questionCount(), seed);

        Random random = new Random(seed);
        int position = 1;
        for (UUID questionId : selected) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < questions.optionCount(questionId); i++) {
                order.add(i);
            }
            Collections.shuffle(order, random);

            attemptQuestions.save(new AttemptQuestion(attempt.getId(), position++,
                    questionId, writeOrder(order)));
        }
    }

    /**
     * An attempt belonging to another account is reported as missing, not
     * forbidden. Confirming it exists would leak that someone else is playing.
     */
    private Attempt requireOwned(UUID attemptId, UUID accountId) {
        Attempt attempt = attempts.findById(attemptId)
                .orElseThrow(() -> ApiException.notFound("attempt"));
        if (!attempt.getAccountId().equals(accountId)) {
            throw ApiException.notFound("attempt");
        }
        return attempt;
    }

    private AttemptQuestion requireQuestionAt(Attempt attempt, int position) {
        if (position < 1 || position > attempt.getScoreDenominator()) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                    "this attempt has no question at position " + position);
        }
        return attemptQuestions.findByAttemptIdAndPosition(attempt.getId(), position)
                .orElseThrow(() -> ApiException.notFound("question"));
    }

    private List<Integer> readOrder(AttemptQuestion frozen) {
        try {
            return objectMapper.readValue(frozen.getOptionOrder(), new TypeReference<>() { });
        } catch (Exception e) {
            // A corrupt order falls back to canonical rather than blocking play.
            return List.of();
        }
    }

    private String writeOrder(List<Integer> order) {
        try {
            return objectMapper.writeValueAsString(order);
        } catch (Exception e) {
            return "[]";
        }
    }
}
