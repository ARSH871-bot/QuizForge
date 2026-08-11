package com.quizforge.identity.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.identity.domain.AuditEvent;
import com.quizforge.identity.repo.AuditEventRepository;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(UUID workspaceId, UUID actorId, String action,
                       String targetType, UUID targetId, Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (Exception e) {
            // Never let an unserialisable detail payload lose the event itself.
            json = "{}";
        }

        events.save(new AuditEvent(UuidV7.generate(), workspaceId, actorId,
                action, targetType, targetId, json));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recentFor(UUID workspaceId) {
        return events.findTop100ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /** Exists only so the append-only trigger can be proven in a test. */
    @Transactional
    public void attemptTamper() {
        events.findAll().stream().findFirst().ifPresent(events::delete);
        events.flush();
    }
}
