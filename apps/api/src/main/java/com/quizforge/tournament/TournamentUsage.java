package com.quizforge.tournament;

import java.util.UUID;

/**
 * How much a tournament has been played.
 *
 * <p>An outbound port, and the direction is the point. The {@code tournament}
 * module needs to know whether a tournament has attempts before allowing it to
 * be deleted, but it cannot depend on {@code play} — {@code play} already
 * depends on it, and a cycle is exactly what the module boundaries exist to
 * prevent.
 *
 * <p>So the interface is declared here, where the need is, and implemented in
 * {@code play}, where the data is. The dependency still runs one way.
 */
public interface TournamentUsage {

    /**
     * How many attempts exist against this tournament, in any state.
     *
     * <p>Counts abandoned and expired attempts too. Someone whose attempt
     * expired still played it, and deleting the tournament would leave their
     * record explaining nothing.
     */
    long attemptCount(UUID tournamentId);
}
