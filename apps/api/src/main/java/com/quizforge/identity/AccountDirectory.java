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

    /** How a person is shown to others: their name, and whether they joined as a guest. */
    record Person(String displayName, boolean guest) {
    }

    /** People by account. Accounts that no longer exist are absent. */
    Map<UUID, Person> peopleOf(Collection<UUID> accountIds);

    /** The workspace's name, or empty if it does not exist. */
    Optional<String> workspaceNameOf(UUID workspaceId);
}
