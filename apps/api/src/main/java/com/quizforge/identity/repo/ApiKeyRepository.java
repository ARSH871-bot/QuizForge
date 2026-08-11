package com.quizforge.identity.repo;

import com.quizforge.identity.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByTokenHash(String tokenHash);
    List<ApiKey> findByWorkspaceIdAndRevokedAtIsNull(UUID workspaceId);
}
