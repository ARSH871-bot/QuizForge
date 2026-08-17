package com.quizforge.identity;

import com.quizforge.identity.domain.Role;

import java.util.UUID;

/**
 * The authenticated caller, and part of identity's published API.
 *
 * <p>It sits in the root package because every module's controllers need it:
 * knowing who is calling is not an identity-internal concern, it is the whole
 * point of authenticating. {@code workspaceId} and {@code role} are null for a
 * session that has not yet selected a workspace; they are always populated for
 * API key authentication, since a key belongs to exactly one workspace.
 */
public record Principal(UUID accountId, UUID workspaceId, Role role, AuthType authType) {

    public enum AuthType { SESSION, API_KEY }

    public boolean can(Role.Permission permission) {
        return role != null && role.can(permission);
    }
}
