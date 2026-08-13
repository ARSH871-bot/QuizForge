package com.quizforge.identity.app;

import com.quizforge.identity.WorkspaceAccess;
import com.quizforge.identity.domain.Role;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Implements the published authorization API over the internal role model. */
@Service
public class WorkspaceAccessAdapter implements WorkspaceAccess {

    private final WorkspaceService workspaces;

    public WorkspaceAccessAdapter(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public boolean canManageContent(UUID workspaceId, UUID accountId) {
        return can(workspaceId, accountId, Role.Permission.MANAGE_CONTENT);
    }

    @Override
    public boolean canManageTournaments(UUID workspaceId, UUID accountId) {
        return can(workspaceId, accountId, Role.Permission.MANAGE_TOURNAMENTS);
    }

    @Override
    public boolean canView(UUID workspaceId, UUID accountId) {
        return can(workspaceId, accountId, Role.Permission.VIEW);
    }

    private boolean can(UUID workspaceId, UUID accountId, Role.Permission permission) {
        Role role = workspaces.roleOf(workspaceId, accountId);
        return role != null && role.can(permission);
    }
}
