package com.quizforge.identity;

import java.util.UUID;

/**
 * Joining a workspace as a player, for the module that owns share links.
 *
 * <p>The only route into a workspace that does not go through an admin. It can
 * only ever grant {@code PLAYER}, and it never changes an existing member's
 * role — an owner who opens their own share link stays an owner.
 */
public interface WorkspaceEnrolment {

    /** The workspace as the caller now sees it, and whether this call joined it. */
    record Enrolled(UUID workspaceId, String name, String slug, String role, boolean joined) {
    }

    Enrolled enrolAsPlayer(UUID accountId, UUID workspaceId);

    /** A new guest, enrolled and holding a session token for the cookie. */
    record GuestEnrolled(Enrolled enrolled, String sessionToken, java.time.Duration sessionLifetime) {
    }

    /**
     * Creates a guest - an account with a name and nothing else - enrols it as
     * a player, and issues it a session.
     *
     * <p>Refuses a name anyone in the workspace already uses, ignoring case,
     * and limits new guest identities per network address.
     */
    GuestEnrolled enrolAsGuest(UUID workspaceId, String nickname, String clientAddress,
                               String userAgent);
}
