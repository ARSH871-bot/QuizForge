package com.quizforge.tournament.repo;

import com.quizforge.tournament.domain.Tournament;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.domain.Limit;

import java.util.UUID;

public interface TournamentRepository extends JpaRepository<Tournament, UUID> {

    List<Tournament> findByWorkspaceId(UUID workspaceId);

    List<Tournament> findByWorkspaceIdOrderByIdDesc(UUID workspaceId, Limit limit);

    List<Tournament> findByWorkspaceIdAndIdLessThanOrderByIdDesc(
            UUID workspaceId, UUID after, Limit limit);

    boolean existsByWorkspaceIdAndSlug(UUID workspaceId, String slug);
}
