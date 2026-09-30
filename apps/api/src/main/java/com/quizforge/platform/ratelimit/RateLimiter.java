package com.quizforge.platform.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * A token bucket per caller, held in Postgres.
 *
 * <p>Each request costs one token. Tokens refill continuously at
 * {@code limit / 60} per second up to {@code limit}, so a caller may burst up to
 * its whole allowance and then proceeds at the sustained rate. A fixed window
 * would let a caller spend everything in the last second of one window and
 * again in the first second of the next — twice the intended rate at exactly
 * the wrong moment.
 */
@Service
public class RateLimiter {

    /** What a caller sees, and what the response headers are built from. */
    public record Decision(boolean allowed, int limit, int remaining, int resetSeconds) {
    }

    private final JdbcTemplate jdbc;
    private final int defaultLimitPerMinute;

    public RateLimiter(JdbcTemplate jdbc,
                       @Value("${quizforge.rate-limit.per-minute:120}") int defaultLimitPerMinute) {
        this.jdbc = jdbc;
        this.defaultLimitPerMinute = defaultLimitPerMinute;
    }

    /**
     * Spends one token, or refuses.
     *
     * <p>Runs in its own transaction and takes a row lock, deliberately. The
     * refill is a read-modify-write, and doing it as one clever statement would
     * still read a pre-update snapshot under {@code READ COMMITTED} — two
     * concurrent requests would each see the same tokens and both spend the
     * last one. {@code SELECT … FOR UPDATE} makes the second wait and see the
     * first's result, which is the whole point of a limiter.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision consume(UUID subjectId, UUID workspaceId) {
        return consume(subjectId, workspaceId, limitFor(workspaceId));
    }

    /**
     * As {@link #consume(UUID, UUID)}, with a limit chosen by the caller rather
     * than the workspace. For budgets that are not a credential's - new guest
     * identities per network address, for one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision consume(UUID subjectId, UUID workspaceId, int limit) {
        double refillPerSecond = limit / 60.0;

        // Create the bucket full, so a caller's first request is not charged for
        // time it did not exist.
        jdbc.update("""
                INSERT INTO rate_limit_bucket (subject_id, workspace_id, tokens, refilled_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (subject_id, workspace_id) DO NOTHING
                """, subjectId, workspaceId, (double) limit);

        List<double[]> rows = jdbc.query("""
                SELECT tokens, EXTRACT(EPOCH FROM (now() - refilled_at))
                FROM rate_limit_bucket
                WHERE subject_id = ? AND workspace_id = ?
                FOR UPDATE
                """, (rs, n) -> new double[]{rs.getDouble(1), rs.getDouble(2)}, subjectId, workspaceId);

        if (rows.isEmpty()) {
            // The row vanished between the two statements - a workspace deleted
            // mid-request. Allow it; the request itself will fail on the same
            // missing workspace with a better message than a 429 would give.
            return new Decision(true, limit, limit, 0);
        }

        double available = Math.min(limit, rows.get(0)[0] + rows.get(0)[1] * refillPerSecond);
        boolean allowed = available >= 1;
        double remaining = allowed ? available - 1 : available;

        jdbc.update("UPDATE rate_limit_bucket SET tokens = ?, refilled_at = now() "
                + "WHERE subject_id = ? AND workspace_id = ?", remaining, subjectId, workspaceId);

        return new Decision(allowed, limit, (int) Math.floor(remaining),
                secondsUntilNextToken(remaining, refillPerSecond));
    }

    /**
     * When the caller can next expect a token.
     *
     * <p>Zero when one is already available. Otherwise the time for the bucket
     * to refill to one, rounded up — rounding down would invite a retry that is
     * still too early, which is a worse answer than being slightly cautious.
     */
    private int secondsUntilNextToken(double remaining, double refillPerSecond) {
        if (remaining >= 1) {
            return 0;
        }
        return (int) Math.ceil((1 - remaining) / refillPerSecond);
    }

    /**
     * The workspace's limit, or the configured default.
     *
     * <p>A column rather than a config key so raising one customer's limit is an
     * {@code UPDATE} rather than a deploy. M7's billing tiers write here.
     */
    @Transactional(readOnly = true)
    public int limitFor(UUID workspaceId) {
        List<Integer> configured = jdbc.query(
                "SELECT rate_limit_per_minute FROM workspace WHERE id = ?",
                (rs, n) -> (Integer) rs.getObject(1), workspaceId);

        return configured.stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(defaultLimitPerMinute);
    }

    /** For tests: empties a caller's bucket so the next request is refused. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void drain(UUID subjectId) {
        jdbc.update("UPDATE rate_limit_bucket SET tokens = 0, refilled_at = now() "
                + "WHERE subject_id = ?", subjectId);
    }

    /** The window the headers describe, for documentation and tests. */
    public static Duration window() {
        return Duration.ofMinutes(1);
    }
}
