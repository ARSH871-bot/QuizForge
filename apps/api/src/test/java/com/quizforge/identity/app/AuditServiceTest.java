package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.domain.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditServiceTest extends AbstractIntegrationTest {

    @Autowired private AuditService audit;
    @Autowired private AccountService accounts;
    @Autowired private WorkspaceService workspaces;

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void recordsAnEventWithItsDetail() {
        UUID actor = newAccount();
        var workspace = workspaces.create(actor, "Acme");

        audit.record(workspace.getId(), actor, "member.role_changed",
                "membership", UUID.randomUUID(), Map.of("from", "VIEWER", "to", "EDITOR"));

        var events = audit.recentFor(workspace.getId());

        // Creating the workspace audits itself, so this is not the only event.
        // Assert on the one under test rather than on the total, which would
        // break again the next time something starts recording.
        var recorded = events.stream()
                .filter(e -> "member.role_changed".equals(e.getAction()))
                .findFirst()
                .orElseThrow();
        assertThat(recorded.getDetail()).contains("VIEWER");
    }

    @Test
    void scopesEventsToTheirWorkspace() {
        UUID actor = newAccount();
        var a = workspaces.create(actor, "Acme");
        var b = workspaces.create(actor, "Globex");

        UUID target = UUID.randomUUID();
        audit.record(a.getId(), actor, "member.added", "account", target, Map.of());

        // The property is scoping, not counting: each workspace now has its own
        // `workspace.created` event, so neither list is empty.
        assertThat(audit.recentFor(a.getId()))
                .extracting(AuditEvent::getAction)
                .contains("member.added");
        assertThat(audit.recentFor(b.getId()))
                .as("one workspace's events must never appear under another")
                .extracting(AuditEvent::getAction)
                .doesNotContain("member.added");
    }

    @Test
    void auditEventsCannotBeDeleted() {
        // Enforced by a database trigger, not by convention: an append-only log
        // the application can rewrite is not evidence of anything.
        UUID actor = newAccount();
        var workspace = workspaces.create(actor, "Acme");
        audit.record(workspace.getId(), actor, "workspace.created",
                "workspace", workspace.getId(), Map.of());

        assertThatThrownBy(() -> audit.attemptTamper())
                .hasMessageContaining("append-only");
    }
}
