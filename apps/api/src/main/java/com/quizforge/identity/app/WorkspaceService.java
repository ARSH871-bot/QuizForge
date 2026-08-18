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
import java.util.Map;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final AuditService audit;

    public WorkspaceService(WorkspaceRepository workspaces, MembershipRepository memberships,
                            AuditService audit) {
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.audit = audit;
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

        audit.record(workspace.getId(), ownerId, "workspace.created", "workspace",
                workspace.getId(), Map.of("name", workspace.getName(),
                        "slug", workspace.getSlug()));

        return workspace;
    }

    /** The workspace, or {@code NOT_FOUND}. Row-Level Security scopes the read. */
    @Transactional(readOnly = true)
    public Workspace require(UUID workspaceId) {
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> ApiException.notFound("workspace"));
    }

    /**
     * Every workspace this account belongs to, with the role it holds.
     *
     * <p>Reads memberships first and resolves workspaces from them, so an
     * account can only ever see workspaces it is a member of - the query has no
     * form in which it could return one it is not.
     */
    @Transactional(readOnly = true)
    public List<Membership> membershipsOf(UUID accountId) {
        return memberships.findByAccountId(accountId);
    }

    /** Resolves several workspaces by id, for rendering a membership list. */
    @Transactional(readOnly = true)
    public List<Workspace> byIds(List<UUID> ids) {
        return workspaces.findAllById(ids);
    }

    @Transactional
    public Workspace rename(UUID workspaceId, UUID actorId, String name) {
        require(workspaceId, actorId, Role.Permission.MANAGE_WORKSPACE);
        if (name == null || name.isBlank()) {
            throw ApiException.invalid("workspace name is required");
        }

        Workspace workspace = require(workspaceId);
        String previous = workspace.getName();
        // The slug is deliberately not regenerated: it was unique at creation
        // and anything already referring to it would break. A rename is
        // cosmetic, not a re-identification.
        workspace.rename(name);
        workspaces.save(workspace);

        audit.record(workspaceId, actorId, "workspace.renamed", "workspace", workspaceId,
                Map.of("from", previous, "to", name));
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

        Membership membership = memberships.save(
                new Membership(UuidV7.generate(), accountId, workspaceId, role));
        audit.record(workspaceId, actorId, "member.added", "account", accountId,
                Map.of("role", role.name()));
        return membership;
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

        Role previous = membership.getRole();
        membership.changeRole(role);
        memberships.save(membership);
        audit.record(workspaceId, actorId, "member.role_changed", "account", accountId,
                Map.of("from", previous.name(), "to", role.name()));
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
        audit.record(workspaceId, actorId, "member.removed", "account", accountId,
                Map.of("role", membership.getRole().name()));
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
