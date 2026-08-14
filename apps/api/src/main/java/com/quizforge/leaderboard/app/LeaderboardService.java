package com.quizforge.leaderboard.app;

import com.quizforge.leaderboard.domain.Standing;
import com.quizforge.leaderboard.repo.StandingRepository;
import com.quizforge.play.AttemptGraded;
import com.quizforge.play.PlayAccess;
import com.quizforge.tournament.ScoringPolicy;
import com.quizforge.tournament.TournamentAccess;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class LeaderboardService {

    private final StandingRepository standings;
    private final PlayAccess play;
    private final TournamentAccess tournaments;

    public LeaderboardService(StandingRepository standings, PlayAccess play,
                              TournamentAccess tournaments) {
        this.standings = standings;
        this.play = play;
        this.tournaments = tournaments;
    }

    /**
     * Rebuilds one standing from the full attempt history whenever an attempt
     * closes.
     *
     * <p>Recomputing rather than incrementing is deliberate. Delivery is
     * at-least-once, so an incrementing handler would double-count a replayed
     * event; a handler that recomputes produces the same row no matter how many
     * times it runs.
     */
    @EventListener
    @Transactional
    public void on(AttemptGraded event) {
        rebuild(event.tournamentId(), event.accountId(), event.workspaceId());
    }

    @Transactional
    public void rebuild(UUID tournamentId, UUID accountId, UUID workspaceId) {
        List<PlayAccess.GradedAttempt> attempts =
                play.gradedAttempts(tournamentId, accountId);

        if (attempts.isEmpty()) {
            return;
        }

        int best = attempts.stream().mapToInt(PlayAccess.GradedAttempt::score).max().orElse(0);
        int total = attempts.stream().mapToInt(PlayAccess.GradedAttempt::score).sum();
        var first = attempts.get(0);
        var last = attempts.get(attempts.size() - 1);

        Standing standing = standings.findByTournamentIdAndAccountId(tournamentId, accountId)
                .orElseGet(() -> new Standing(tournamentId, accountId, workspaceId));

        standing.recomputeFrom(attempts.size(), best, total, first.score(), last.score(),
                first.outOf(), first.gradedAt(), last.gradedAt());

        standings.save(standing);
    }

    /**
     * The ranked leaderboard, with the tournament's scoring policy applied at
     * read time.
     *
     * <p>Ties break by who got there first — the earlier {@code firstGradedAt}
     * ranks higher. An arbitrary tie-break would make the order unstable
     * between reads, which looks like a bug to anyone watching a leaderboard.
     */
    @Transactional(readOnly = true)
    public List<LeaderboardEntry> standings(UUID tournamentId, int limit) {
        ScoringPolicy policy = tournaments.scoringPolicyOf(tournamentId);

        List<LeaderboardEntry> entries = standings.findByTournamentId(tournamentId).stream()
                .map(s -> new LeaderboardEntry(
                        s.getAccountId(),
                        resolve(policy, s),
                        s.getOutOf(),
                        s.getAttempts(),
                        s.getFirstGradedAt(),
                        0))
                .sorted(Comparator.comparingDouble(LeaderboardEntry::score).reversed()
                        .thenComparing(LeaderboardEntry::firstGradedAt))
                .toList();

        // Rank is assigned after sorting so it always matches the order shown.
        List<LeaderboardEntry> ranked = new java.util.ArrayList<>();
        int rank = 1;
        for (LeaderboardEntry entry : entries) {
            ranked.add(new LeaderboardEntry(entry.accountId(), entry.score(), entry.outOf(),
                    entry.attempts(), entry.firstGradedAt(), rank++));
            if (ranked.size() >= limit) {
                break;
            }
        }
        return ranked;
    }

    private double resolve(ScoringPolicy policy, Standing standing) {
        return switch (policy) {
            case BEST -> standing.getBestScore();
            case FIRST -> standing.getFirstScore();
            case LAST -> standing.getLastScore();
            case AVERAGE -> (double) standing.getTotalScore() / standing.getAttempts();
        };
    }
}
