package com.quizforge.leaderboard.repo;

import com.quizforge.leaderboard.domain.Standing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StandingRepository extends JpaRepository<Standing, Standing.Key> {

    List<Standing> findByTournamentId(UUID tournamentId);

    Optional<Standing> findByTournamentIdAndAccountId(UUID tournamentId, UUID accountId);
}
