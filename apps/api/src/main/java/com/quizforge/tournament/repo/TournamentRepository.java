package com.quizforge.tournament.repo;

import com.quizforge.tournament.domain.Tournament;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TournamentRepository extends JpaRepository<Tournament, UUID> {

    List<Tournament> findByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceIdAndSlug(UUID workspaceId, String slug);
}
