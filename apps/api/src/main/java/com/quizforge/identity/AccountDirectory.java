package com.quizforge.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Names, for modules that show people and workspaces to other people.
 *
 * <p>Returns display names only. Email addresses stay inside identity: a
 * leaderboard needs to say who is winning, not how to contact them.
 */
public interface AccountDirectory {

    /** Display names by account. Accounts that no longer exist are absent. */
    Map<UUID, String> displayNamesOf(Collection<UUID> accountIds);

    /** The workspace's name, or empty if it does not exist. */
    Optional<String> workspaceNameOf(UUID workspaceId);
}
