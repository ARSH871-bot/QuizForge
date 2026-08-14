package com.quizforge.tournament.app;

import com.quizforge.platform.error.ApiException;
import com.quizforge.tournament.TournamentAccess;
import com.quizforge.tournament.domain.Tournament;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Implements the published tournament API over the internal service. */
@Service
public class TournamentAccessAdapter implements TournamentAccess {

    private final TournamentService tournaments;

    public TournamentAccessAdapter(TournamentService tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    @Transactional(readOnly = true)
    public PlayableTournament requireOpen(UUID tournamentId) {
        Tournament tournament = tournaments.requireById(tournamentId);
        Instant now = Instant.now();

        if (!tournament.isOpen(now)) {
            throw ApiException.invalid("this tournament is "
                    + tournament.state(now).name().toLowerCase());
        }

        return new PlayableTournament(tournament.getId(), tournament.getWorkspaceId(),
                tournament.getBankId(), tournament.getQuestionCount(),
                tournament.getTimeLimitSeconds(), tournament.getMaxAttempts());
    }
}
