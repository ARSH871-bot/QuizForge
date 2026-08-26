package com.quizforge.tournament.app;

import com.quizforge.content.QuestionAccess;
import com.quizforge.identity.WorkspaceAccess;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import com.quizforge.tournament.ScoringPolicy;
import com.quizforge.tournament.TournamentUsage;
import com.quizforge.tournament.domain.Tournament;
import com.quizforge.tournament.domain.TournamentState;
import com.quizforge.tournament.repo.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TournamentService {

    private final TournamentRepository tournaments;
    private final WorkspaceAccess access;
    private final QuestionAccess questions;
    private final TournamentUsage usage;

    public TournamentService(TournamentRepository tournaments, WorkspaceAccess access,
                             QuestionAccess questions, TournamentUsage usage) {
        this.tournaments = tournaments;
        this.access = access;
        this.questions = questions;
        this.usage = usage;
    }

    @Transactional
    public Tournament create(UUID workspaceId, UUID actorId, TournamentDraft draft) {
        if (!access.canManageTournaments(workspaceId, actorId)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "you do not have permission to manage tournaments in this workspace");
        }

        validate(workspaceId, draft);

        Tournament tournament = new Tournament(
                UuidV7.generate(), workspaceId, draft.bankId(), draft.name(),
                uniqueSlug(workspaceId, draft.name()), draft.opensAt(), draft.closesAt(),
                draft.questionCount(), draft.timeLimitSeconds(), draft.maxAttempts(),
                draft.scoringPolicy() == null ? ScoringPolicy.FIRST : draft.scoringPolicy(),
                actorId);

        return tournaments.save(tournament);
    }

    /**
     * Amends a tournament that has not opened yet.
     *
     * <p>Refused once it is {@code OPEN} or {@code CLOSED}. A tournament in
     * progress has players who started under its current rules, and one that
     * has closed has results those rules explain. Neither can be edited without
     * making the record dishonest.
     *
     * <p>The draft is validated exactly as it is at creation, including the
     * check that the bank holds enough questions - a bank can lose questions to
     * retirement between scheduling and amending.
     */
    @Transactional
    public Tournament update(UUID tournamentId, UUID actorId, TournamentDraft draft) {
        Tournament tournament = requireById(tournamentId);
        requirePermission(tournament.getWorkspaceId(), actorId);

        Instant now = Instant.now();
        if (tournament.state(now) != TournamentState.SCHEDULED) {
            throw ApiException.invalid("a tournament can only be changed before it opens; this one is "
                    + tournament.state(now).name().toLowerCase(Locale.ROOT));
        }

        validate(tournament.getWorkspaceId(), draft);

        tournament.rename(draft.name());
        tournament.reschedule(draft.opensAt(), draft.closesAt(), now);
        tournament.amendRules(draft.questionCount(), draft.timeLimitSeconds(),
                draft.maxAttempts(),
                draft.scoringPolicy() == null ? ScoringPolicy.FIRST : draft.scoringPolicy(),
                now);

        return tournaments.save(tournament);
    }

    /**
     * Deletes a tournament nobody has played.
     *
     * <p>Deliberately a real delete rather than an archive, and deliberately
     * narrow. A tournament with attempts is somebody's record; removing it would
     * leave scores and standings that nothing explains. A scheduled tournament
     * with no attempts is just a plan, and deleting a plan loses nothing.
     *
     * <p>The attempt count comes through {@link TournamentUsage}, an outbound
     * port implemented in {@code play} - this module cannot depend on
     * {@code play}, which already depends on it.
     */
    @Transactional
    public void delete(UUID tournamentId, UUID actorId) {
        Tournament tournament = requireById(tournamentId);
        requirePermission(tournament.getWorkspaceId(), actorId);

        Instant now = Instant.now();
        if (tournament.state(now) != TournamentState.SCHEDULED) {
            throw ApiException.invalid("only a scheduled tournament can be deleted; this one is "
                    + tournament.state(now).name().toLowerCase(Locale.ROOT));
        }

        long attempts = usage.attemptCount(tournamentId);
        if (attempts > 0) {
            throw ApiException.invalid("this tournament has " + attempts
                    + " attempt(s) and cannot be deleted");
        }

        tournaments.delete(tournament);
    }

    @Transactional(readOnly = true)
    public Tournament requireById(UUID tournamentId) {
        return tournaments.findById(tournamentId)
                .orElseThrow(() -> ApiException.notFound("tournament"));
    }

    @Transactional(readOnly = true)
    public List<Tournament> inWorkspace(UUID workspaceId) {
        return tournaments.findByWorkspaceId(workspaceId);
    }

    private void requirePermission(UUID workspaceId, UUID actorId) {
        if (!access.canManageTournaments(workspaceId, actorId)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "you do not have permission to manage tournaments in this workspace");
        }
    }

    private void validate(UUID workspaceId, TournamentDraft draft) {
        if (draft.name() == null || draft.name().isBlank()) {
            throw ApiException.invalid("tournament name is required");
        }
        if (draft.opensAt() == null || draft.closesAt() == null) {
            throw ApiException.invalid("a tournament needs an opening and closing time");
        }
        if (!draft.closesAt().isAfter(draft.opensAt())) {
            throw ApiException.invalid("a tournament cannot close before it opens");
        }
        if (draft.questionCount() < 1) {
            throw ApiException.invalid("a tournament needs at least one question");
        }
        if (draft.maxAttempts() < 1) {
            throw ApiException.invalid("a tournament needs at least one permitted attempt");
        }
        if (draft.timeLimitSeconds() != null && draft.timeLimitSeconds() <= 0) {
            throw ApiException.invalid("a time limit must be greater than zero");
        }

        if (!questions.bankBelongsTo(draft.bankId(), workspaceId)) {
            // Not "forbidden" - a bank in another workspace must be
            // indistinguishable from one that does not exist.
            throw ApiException.notFound("question bank");
        }

        // Rejected here rather than at play. A tournament that cannot be played
        // must never be schedulable; discovering it when the first player
        // arrives is far worse than refusing it now.
        int available = questions.currentQuestionCount(draft.bankId());
        if (available < draft.questionCount()) {
            throw ApiException.invalid("the bank holds only " + available
                    + " questions, but the tournament asks for " + draft.questionCount());
        }
    }

    private String uniqueSlug(UUID workspaceId, String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "tournament";
        }
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }

        String candidate = base;
        int suffix = 2;
        while (tournaments.existsByWorkspaceIdAndSlug(workspaceId, candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
