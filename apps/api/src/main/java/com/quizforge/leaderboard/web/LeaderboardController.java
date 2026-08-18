package com.quizforge.leaderboard.web;

import com.quizforge.identity.Principal;
import com.quizforge.leaderboard.app.LeaderboardEntry;
import com.quizforge.leaderboard.app.LeaderboardService;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    /**
     * A tournament's ranked standings, scoped to the caller's workspace.
     *
     * <p>The workspace requirement is load-bearing, not ceremony. Tenant
     * isolation here rests on Row-Level Security, and RLS only engages once
     * {@code TenantContext} is populated - which happens only when the
     * workspace header is present. Without it the connection runs as the owning
     * role, PostgreSQL skips RLS for superusers, and this query would return any
     * tournament's standings to any authenticated caller.
     *
     * <p>So this check is the difference between isolated and not. It is not a
     * convenience for selecting which workspace to read.
     */
    @GetMapping("/{tournamentId}/standings")
    public List<LeaderboardEntry> standings(
            @PathVariable String tournamentId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Principal principal) {

        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }

        return leaderboard.standings(TypeId.parse("trn", tournamentId),
                Math.clamp(limit, 1, MAX_LIMIT));
    }
}
