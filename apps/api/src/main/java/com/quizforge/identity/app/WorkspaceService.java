package com.quizforge.identity.app;

import com.quizforge.identity.domain.Membership;
import com.quizforge.identity.domain.Role;
import com.quizforge.identity.domain.Workspace;
import com.quizforge.identity.repo.MembershipRepository;
import com.quizforge.identity.repo.WorkspaceRepository;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;

    public WorkspaceService(WorkspaceRepository workspaces, MembershipRepository memberships) {
        this.workspaces = workspaces;
        this.memberships = memberships;
    }

    @Transactional
    public Workspace create(UUID ownerId, String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.invalid("workspace name is required");
        }

        Workspace workspace = new Workspace(UuidV7.generate(), name, uniqueSlug(name), ownerId);
        workspaces.save(workspace);

        memberships.save(new Membership(
                UuidV7.generate(), ownerId, workspace.getId(), Role.OWNER));

        return workspace;
    }

    @Transactional(readOnly = true)
    public Role roleOf(UUID workspaceId, UUID accountId) {
        return memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId)
                .map(Membership::getRole)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Membership> membersOf(UUID workspaceId) {
        return memberships.findByWorkspaceId(workspaceId);
    }

    @Transactional
    public Membership addMember(UUID workspaceId, UUID actorId, UUID accountId, Role role) {
        require(workspaceId, actorId, Role.Permission.MANAGE_MEMBERS);

        memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId).ifPresent(m -> {
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "that account is already a member of this workspace");
        });

        return memberships.save(new Membership(UuidV7.generate(), accountId, workspaceId, role));
    }

    @Transactional
    public void changeRole(UUID workspaceId, UUID actorId, UUID accountId, Role role) {
        require(workspaceId, actorId, Role.Permission.MANAGE_MEMBERS);

        Membership membership = memberships
                .findByWorkspaceIdAndAccountId(workspaceId, accountId)
                .orElseThrow(() -> ApiException.notFound("membership"));

        if (membership.getRole() == Role.OWNER && role != Role.OWNER) {
            guardLastOwner(workspaceId);
        }

        membership.changeRole(role);
        memberships.save(membership);
    }

    @Transactional
    public void removeMember(UUID workspaceId, UUID actorId, UUID accountId) {
        require(workspaceId, actorId, Role.Permission.MANAGE_MEMBERS);

        Membership membership = memberships
                .findByWorkspaceIdAndAccountId(workspaceId, accountId)
                .orElseThrow(() -> ApiException.notFound("membership"));

        if (membership.getRole() == Role.OWNER) {
            guardLastOwner(workspaceId);
        }

        memberships.delete(membership);
    }

    private void guardLastOwner(UUID workspaceId) {
        if (memberships.countByWorkspaceIdAndRole(workspaceId, Role.OWNER) <= 1) {
            throw ApiException.invalid("cannot remove or demote the last owner of a workspace");
        }
    }

    private void require(UUID workspaceId, UUID actorId, Role.Permission permission) {
        Role role = roleOf(workspaceId, actorId);
        if (role == null || !role.can(permission)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "you do not have permission to perform that action");
        }
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "workspace";
        }
        if (base.length() > 48) {
            base = base.substring(0, 48);
        }

        String candidate = base;
        int suffix = 2;
        while (workspaces.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
