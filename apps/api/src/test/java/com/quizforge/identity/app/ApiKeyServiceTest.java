package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyServiceTest extends AbstractIntegrationTest {

    @Autowired private ApiKeyService apiKeys;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void issuesAKeyWithTheCorrectEnvironmentPrefix() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        var live = apiKeys.issue(workspace.getId(), owner, "production", true);
        var test = apiKeys.issue(workspace.getId(), owner, "sandbox", false);

        assertThat(live.secret()).startsWith("qf_live_");
        assertThat(test.secret()).startsWith("qf_test_");
    }

    @Test
    void storesOnlyADigestAndTheLastFourCharacters() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        var issued = apiKeys.issue(workspace.getId(), owner, "production", true);

        assertThat(issued.key().getTokenHash()).isNotEqualTo(issued.secret());
        assertThat(issued.secret()).endsWith(issued.key().getLastFour());
    }

    @Test
    void resolvesAnIssuedKey() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        var issued = apiKeys.issue(workspace.getId(), owner, "production", true);

        assertThat(apiKeys.resolve(issued.secret()))
                .isPresent()
                .get()
                .satisfies(k -> assertThat(k.getWorkspaceId()).isEqualTo(workspace.getId()));
    }

    @Test
    void doesNotResolveARevokedKey() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        var issued = apiKeys.issue(workspace.getId(), owner, "production", true);

        apiKeys.revoke(workspace.getId(), owner, issued.key().getId());

        assertThat(apiKeys.resolve(issued.secret())).isEmpty();
    }

    @Test
    void requiresWorkspaceManagementPermission() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        UUID viewer = newAccount();
        workspaces.addMember(workspace.getId(), owner, viewer, Role.VIEWER);

        assertThatThrownBy(() -> apiKeys.issue(workspace.getId(), viewer, "sneaky", true))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    void doesNotRevealThatAKeyExistsInAnotherWorkspace() {
        UUID ownerA = newAccount();
        var workspaceA = workspaces.create(ownerA, "Acme");
        var issued = apiKeys.issue(workspaceA.getId(), ownerA, "production", true);

        UUID ownerB = newAccount();
        var workspaceB = workspaces.create(ownerB, "Globex");

        assertThatThrownBy(() ->
                apiKeys.revoke(workspaceB.getId(), ownerB, issued.key().getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.NOT_FOUND));

        assertThat(apiKeys.resolve(issued.secret())).isPresent();
    }
}
