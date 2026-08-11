package com.quizforge.identity.domain;

import java.util.Set;

/**
 * Workspace roles, ordered from most to least privileged. Permissions are
 * modelled explicitly rather than by ordinal comparison, so that adding a role
 * that is not a strict superset of a weaker one stays possible.
 */
public enum Role {

    OWNER(Permission.values()),
    ADMIN(Permission.MANAGE_MEMBERS, Permission.MANAGE_CONTENT,
          Permission.MANAGE_TOURNAMENTS, Permission.VIEW),
    EDITOR(Permission.MANAGE_CONTENT, Permission.MANAGE_TOURNAMENTS, Permission.VIEW),
    VIEWER(Permission.VIEW);

    public enum Permission {
        /** Rename or delete the workspace, manage billing, transfer ownership. */
        MANAGE_WORKSPACE,
        /** Invite, remove, and change the role of members. */
        MANAGE_MEMBERS,
        /** Create and edit question banks and questions. */
        MANAGE_CONTENT,
        /** Create, schedule, and close tournaments. */
        MANAGE_TOURNAMENTS,
        /** Read workspace data and results. */
        VIEW
    }

    private final Set<Permission> permissions;

    Role(Permission... permissions) {
        this.permissions = Set.of(permissions);
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
