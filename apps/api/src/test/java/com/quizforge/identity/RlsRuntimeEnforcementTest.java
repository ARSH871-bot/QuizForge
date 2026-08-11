package com.quizforge.identity;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.ApiKeyService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.repo.ApiKeyRepository;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Row-Level Security is enforced by the running application, not merely
 * that policies exist. The distinction matters: PostgreSQL silently skips RLS
 * for superusers, so a policy can be present and enforce nothing at all.
 */
class RlsRuntimeEnforcementTest extends AbstractIntegrationTest {

    @Autowired private AccountService accounts;
    @Autowired private WorkspaceService workspaces;
    @Autowired private ApiKeyService apiKeys;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private TransactionTemplate transactions;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void aWorkspaceCannotSeeAnotherWorkspacesApiKeys() {
        UUID ownerA = newAccount();
        var workspaceA = workspaces.create(ownerA, "Acme");
        apiKeys.issue(workspaceA.getId(), ownerA, "production", true);

        UUID ownerB = newAccount();
        var workspaceB = workspaces.create(ownerB, "Globex");

        // With workspace A in scope, A's key is visible.
        TenantContext.set(workspaceA.getId());
        long visibleFromA = transactions.execute(status ->
                (long) apiKeyRepository.findByWorkspaceIdAndRevokedAtIsNull(
                        workspaceA.getId()).size());
        TenantContext.clear();

        // With workspace B in scope, the same query returns nothing - the
        // database refuses, regardless of what the query asked for.
        TenantContext.set(workspaceB.getId());
        long visibleFromB = transactions.execute(status ->
                (long) apiKeyRepository.findByWorkspaceIdAndRevokedAtIsNull(
                        workspaceA.getId()).size());
        TenantContext.clear();

        assertThat(visibleFromA).as("workspace A must see its own key").isEqualTo(1);
        assertThat(visibleFromB)
                .as("workspace B must not see workspace A's key, even when asking for it")
                .isZero();
    }

    @Test
    void theSessionRoleIsDroppedWhenATenantIsInScope() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        TenantContext.set(workspace.getId());
        String role = transactions.execute(status ->
                apiKeyRepository.currentDatabaseRole());
        TenantContext.clear();

        assertThat(role)
                .as("the transaction must run as the restricted role, or RLS is skipped")
                .isEqualTo("quizforge_app");
    }
}
