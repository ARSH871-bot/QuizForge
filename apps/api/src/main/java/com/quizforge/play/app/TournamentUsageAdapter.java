package com.quizforge.play.app;

import com.quizforge.play.repo.AttemptRepository;
import com.quizforge.tournament.TournamentUsage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Answers {@link TournamentUsage} for the {@code tournament} module.
 *
 * <p>Lives here because this is where attempts are. The interface lives in
 * {@code tournament} because that is where the question is asked — which keeps
 * the dependency running {@code play} to {@code tournament}, the direction it
 * already runs.
 */
@Service
public class TournamentUsageAdapter implements TournamentUsage {

    private final AttemptRepository attempts;

    public TournamentUsageAdapter(AttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Override
    @Transactional(readOnly = true)
    public long attemptCount(UUID tournamentId) {
        return attempts.countByTournamentId(tournamentId);
    }
}
