package com.quizforge.leaderboard.web;

import com.quizforge.leaderboard.app.LeaderboardEntry;
import com.quizforge.leaderboard.app.LeaderboardService;
import com.quizforge.platform.id.TypeId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/tournaments")
public class LeaderboardController {

    /** Capped so a caller cannot ask for an unbounded page. */
    private static final int MAX_LIMIT = 100;

    private final LeaderboardService leaderboard;

    public LeaderboardController(LeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    @GetMapping("/{tournamentId}/standings")
    public List<LeaderboardEntry> standings(
            @PathVariable String tournamentId,
            @RequestParam(defaultValue = "20") int limit) {

        return leaderboard.standings(TypeId.parse("trn", tournamentId),
                Math.clamp(limit, 1, MAX_LIMIT));
    }
}
