package com.quizforge.identity.repo;

import com.quizforge.identity.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;

import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByTokenHash(String tokenHash);
    List<ApiKey> findByWorkspaceIdAndRevokedAtIsNull(UUID workspaceId);

    List<ApiKey> findByWorkspaceIdAndRevokedAtIsNullOrderByIdDesc(UUID workspaceId, Limit limit);

    List<ApiKey> findByWorkspaceIdAndRevokedAtIsNullAndIdLessThanOrderByIdDesc(
            UUID workspaceId, UUID after, Limit limit);

    /** Reports the effective database role, so tests can prove RLS applies. */
    @Query(value = "SELECT current_user", nativeQuery = true)
    String currentDatabaseRole();
}
