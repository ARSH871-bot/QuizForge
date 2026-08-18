package com.quizforge.leaderboard.web;

import com.quizforge.api.LeaderboardsApi;
import com.quizforge.api.model.LeaderboardEntry;
import com.quizforge.api.model.StandingPage;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.leaderboard.app.LeaderboardService;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * Ranked standings. Implements {@link LeaderboardsApi}, generated from
 * {@code openapi.yaml}.
 */
@RestController
public class LeaderboardController implements LeaderboardsApi {

    /** The largest page a caller may request. Asking for more is an error. */
    private static final int MAX_LIMIT = 100;

    /** What the contract documents as the default when no limit is sent. */
    private static final int DEFAULT_LIMIT = 20;

    private final LeaderboardService leaderboard;

    public LeaderboardController(LeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    /**
     * The workspace requirement is load-bearing, not ceremony. Tenant isolation
     * here rests on Row-Level Security, and RLS only engages once
     * {@code TenantContext} is populated, which happens only when the workspace
     * header is present. Without it the connection runs as the owning role,
     * PostgreSQL skips RLS for superusers, and this query would return the
     * standings of any tournament to any authenticated caller.
     */
    @Override
    public ResponseEntity<StandingPage> getStandings(String tournamentId,
                                                     String workspaceHeader,
                                                     Integer limit) {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }

        int requested = limit == null ? DEFAULT_LIMIT : limit;
        if (requested < 1 || requested > MAX_LIMIT) {
            // Rejected rather than clamped. Clamping answered a request for
            // 1000 with 100 rows, which is indistinguishable from a tournament
            // that only has 100 - so a client concluded it held the whole
            // leaderboard when it held a prefix. Silently correcting input
            // produces wrong answers rather than errors.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT);
        }

        StandingPage page = new StandingPage(
                leaderboard.standings(TypeId.parse("trn", tournamentId), requested).stream()
                        .map(this::represent)
                        .toList());
        page.setNextCursor(null);
        return ResponseEntity.ok(page);
    }

    /**
     * Renders the account with its {@code acc_} prefix, so it matches the
     * identifier {@code GET /v1/auth/me} returns for the same account.
     */
    private LeaderboardEntry represent(com.quizforge.leaderboard.app.LeaderboardEntry entry) {
        return new LeaderboardEntry(
                TypeId.render("acc", entry.accountId()),
                entry.score(),
                entry.outOf(),
                entry.attempts(),
                entry.firstGradedAt().atOffset(ZoneOffset.UTC),
                entry.rank());
    }
}
