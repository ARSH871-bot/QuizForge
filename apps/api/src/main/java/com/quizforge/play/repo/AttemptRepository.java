package com.quizforge.play.repo;

import com.quizforge.play.domain.Attempt;
import com.quizforge.play.domain.AttemptState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    List<Attempt> findByTournamentIdAndAccountId(UUID tournamentId, UUID accountId);

    long countByTournamentIdAndAccountId(UUID tournamentId, UUID accountId);

    /** Every attempt against a tournament, in any state. */
    long countByTournamentId(UUID tournamentId);

    /** The organiser's view of who has played, newest first. */
    List<Attempt> findByTournamentIdOrderByStartedAtDesc(UUID tournamentId);

    /** Drives the expiry sweep. */
    List<Attempt> findByStateAndExpiresAtBefore(AttemptState state, Instant at);
}
