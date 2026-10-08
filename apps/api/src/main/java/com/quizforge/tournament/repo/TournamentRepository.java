package com.quizforge.tournament.repo;

import com.quizforge.tournament.domain.Tournament;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TournamentRepository extends JpaRepository<Tournament, UUID> {

    List<Tournament> findByWorkspaceId(UUID workspaceId);

    List<Tournament> findByWorkspaceIdOrderByIdDesc(UUID workspaceId, Limit limit);

    List<Tournament> findByWorkspaceIdAndIdLessThanOrderByIdDesc(
            UUID workspaceId, UUID after, Limit limit);

    boolean existsByWorkspaceIdAndSlug(UUID workspaceId, String slug);

    /** {@code FOR SHARE}: an attempt starting. Players never wait for each other. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from Tournament t where t.id = :id")
    Optional<Tournament> findForPlayById(@Param("id") UUID id);

    /** Exclusive: an organiser's change, which waits for any attempt in flight. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tournament t where t.id = :id")
    Optional<Tournament> findForChangeById(@Param("id") UUID id);
}
