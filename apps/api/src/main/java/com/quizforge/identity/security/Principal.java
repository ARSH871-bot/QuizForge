package com.quizforge.identity.security;

import com.quizforge.identity.domain.Role;

import java.util.UUID;

/**
 * The authenticated caller. {@code workspaceId} and {@code role} are null for a
 * session that has not yet selected a workspace; they are always populated for
 * API key authentication, since a key belongs to exactly one workspace.
 */
public record Principal(UUID accountId, UUID workspaceId, Role role, AuthType authType) {

    public enum AuthType { SESSION, API_KEY }

    public boolean can(Role.Permission permission) {
        return role != null && role.can(permission);
    }
}
