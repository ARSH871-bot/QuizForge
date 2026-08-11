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

class WorkspaceServiceTest extends AbstractIntegrationTest {

    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void creatorBecomesOwner() {
        UUID owner = newAccount();

        var workspace = workspaces.create(owner, "Acme Training");

        assertThat(workspace.getName()).isEqualTo("Acme Training");
        assertThat(workspaces.roleOf(workspace.getId(), owner)).isEqualTo(Role.OWNER);
    }

    @Test
    void generatesAUniqueSlugWhenNamesCollide() {
        var first = workspaces.create(newAccount(), "Acme Training");
        var second = workspaces.create(newAccount(), "Acme Training");

        assertThat(first.getSlug()).isNotEqualTo(second.getSlug());
    }

    @Test
    void adminsCanAddMembersButViewersCannot() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        UUID viewer = newAccount();
        workspaces.addMember(workspace.getId(), owner, viewer, Role.VIEWER);

        UUID outsider = newAccount();
        assertThatThrownBy(() ->
                workspaces.addMember(workspace.getId(), viewer, outsider, Role.EDITOR))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    void refusesToRemoveTheLastOwner() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        assertThatThrownBy(() -> workspaces.removeMember(workspace.getId(), owner, owner))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("last owner");
    }

    @Test
    void refusesToDemoteTheLastOwner() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        assertThatThrownBy(() ->
                workspaces.changeRole(workspace.getId(), owner, owner, Role.ADMIN))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("last owner");
    }

    @Test
    void nonMembersHaveNoRole() {
        var workspace = workspaces.create(newAccount(), "Acme");

        assertThat(workspaces.roleOf(workspace.getId(), newAccount())).isNull();
    }
}
