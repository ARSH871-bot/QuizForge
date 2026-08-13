package com.quizforge.identity;

import java.util.UUID;

/**
 * The identity module's published authorization API.
 *
 * <p>Other modules ask permission questions through this interface rather than
 * reaching into {@code identity.app} or {@code identity.domain}. That keeps
 * roles, memberships and the permission matrix internal to identity: how a
 * role grants a permission can change without touching a single consumer, and
 * consumers cannot accumulate opinions about identity's internals.
 *
 * <p>Deliberately returns booleans rather than a {@code Role}. A caller that
 * receives a role starts branching on it, which reimplements the permission
 * matrix in the wrong module.
 */
public interface WorkspaceAccess {

    /** Whether the account may create and edit content in the workspace. */
    boolean canManageContent(UUID workspaceId, UUID accountId);

    /** Whether the account may create and schedule tournaments. */
    boolean canManageTournaments(UUID workspaceId, UUID accountId);

    /** Whether the account may read workspace data at all. */
    boolean canView(UUID workspaceId, UUID accountId);
}
