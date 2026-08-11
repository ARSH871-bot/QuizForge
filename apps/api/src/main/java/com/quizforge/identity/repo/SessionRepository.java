package com.quizforge.identity.repo;

import com.quizforge.identity.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    Optional<Session> findByTokenHash(String tokenHash);
    List<Session> findByAccountIdAndRevokedAtIsNull(UUID accountId);
}
