package com.quizforge.tournament.app;

import com.quizforge.content.QuestionAccess;
import com.quizforge.identity.WorkspaceAccess;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import com.quizforge.tournament.ScoringPolicy;
import com.quizforge.tournament.domain.Tournament;
import com.quizforge.tournament.repo.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TournamentService {

    private final TournamentRepository tournaments;
    private final WorkspaceAccess access;
    private final QuestionAccess questions;

    public TournamentService(TournamentRepository tournaments, WorkspaceAccess access,
                             QuestionAccess questions) {
        this.tournaments = tournaments;
        this.access = access;
        this.questions = questions;
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

    @Transactional(readOnly = true)
    public Tournament requireById(UUID tournamentId) {
        return tournaments.findById(tournamentId)
                .orElseThrow(() -> ApiException.notFound("tournament"));
    }

    @Transactional(readOnly = true)
    public List<Tournament> inWorkspace(UUID workspaceId) {
        return tournaments.findByWorkspaceId(workspaceId);
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
