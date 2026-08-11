package com.quizforge.identity.repo;

import com.quizforge.identity.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findTop100ByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}
