package com.quizforge.play.app;

import com.quizforge.play.PlayAccess;
import com.quizforge.play.domain.Attempt;
import com.quizforge.play.repo.AttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Implements the published play API over the internal repositories. */
@Service
public class PlayAccessAdapter implements PlayAccess {

    private final AttemptRepository attempts;

    public PlayAccessAdapter(AttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GradedAttempt> gradedAttempts(UUID tournamentId, UUID accountId) {
        return attempts.findByTournamentIdAndAccountId(tournamentId, accountId).stream()
                .filter(a -> a.getState().isTerminal())
                .sorted(Comparator.comparing(Attempt::getGradedAt))
                .map(a -> new GradedAttempt(a.getId(), a.getScoreNumerator(),
                        a.getScoreDenominator(), a.getGradedAt()))
                .toList();
    }
}
