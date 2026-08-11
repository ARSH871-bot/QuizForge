package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
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

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo("member.role_changed");
        assertThat(events.get(0).getDetail()).contains("VIEWER");
    }

    @Test
    void scopesEventsToTheirWorkspace() {
        UUID actor = newAccount();
        var a = workspaces.create(actor, "Acme");
        var b = workspaces.create(actor, "Globex");

        audit.record(a.getId(), actor, "workspace.created", "workspace", a.getId(), Map.of());

        assertThat(audit.recentFor(a.getId())).hasSize(1);
        assertThat(audit.recentFor(b.getId())).isEmpty();
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
