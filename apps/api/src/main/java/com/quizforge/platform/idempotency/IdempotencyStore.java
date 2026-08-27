package com.quizforge.platform.idempotency;

import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Claims and records idempotency keys.
 *
 * <p>Every method here runs in its <strong>own</strong> transaction. That is
 * the whole mechanism, not a detail: the claim has to be committed and visible
 * to other connections <em>before</em> the request it guards begins, or a
 * concurrent retry would not see it and both would do the work.
 */
@Service
public class IdempotencyStore {

    /** How long a key is honoured. After this the purge removes it. */
    public static final int REPLAY_WINDOW_HOURS = 24;

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A completed request, ready to be replayed. */
    public record Recorded(int status, String body) {
    }

    /** What happened when a key was presented. */
    public sealed interface Claim {

        /** The key is ours; run the request and record the outcome. */
        record Granted() implements Claim {
        }

        /** The key belongs to a finished request; replay its response. */
        record Replay(Recorded response) implements Claim {
        }

        /** The original request is still running somewhere. */
        record InFlight() implements Claim {
        }
    }

    /**
     * Attempts to claim a key.
     *
     * <p>The insert is the lock. {@code ON CONFLICT DO NOTHING} makes the race
     * a database problem rather than an application one: exactly one caller
     * inserts the row, whatever the interleaving, because the primary key
     * admits one. Checking for an existing row first and inserting second would
     * leave a window between the two in which both callers see nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(UUID workspaceId, String key, String requestHash) {
        int inserted;
        try {
            inserted = jdbc.update("""
                    INSERT INTO idempotency_key (workspace_id, key, request_hash)
                    VALUES (?, ?, ?)
                    ON CONFLICT (workspace_id, key) DO NOTHING
                    """, workspaceId, key, requestHash);
        } catch (DataIntegrityViolationException e) {
            // A workspace that vanished between the request and here. Treat it
            // as a claim rather than a 500: the request itself will fail on the
            // same missing row, with a better message than this could give.
            return new Claim.Granted();
        }

        if (inserted == 1) {
            return new Claim.Granted();
        }

        Map<String, Object> existing = jdbc.queryForMap("""
                SELECT request_hash, response_status, response_body
                FROM idempotency_key
                WHERE workspace_id = ? AND key = ?
                """, workspaceId, key);

        // Constant-time, though the hash is not a secret: it is derived from
        // the caller's own request. The cost is nil and it removes the question
        // of whether a caller who already holds the key could learn anything
        // from how quickly a mismatch comes back.
        String storedHash = String.valueOf(existing.get("request_hash"));
        boolean sameRequest = MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8));

        if (!sameRequest) {
            // Almost always a key that was reused rather than regenerated.
            // Answering with the earlier response would be a correct answer to
            // a different question, which is worse than refusing.
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "that Idempotency-Key was used for a different request");
        }

        Integer status = (Integer) existing.get("response_status");
        return status == null
                ? new Claim.InFlight()
                : new Claim.Replay(new Recorded(status, (String) existing.get("response_body")));
    }

    /**
     * Records the response, so a later retry can be answered without repeating
     * the work.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID workspaceId, String key, int status, String body) {
        jdbc.update("""
                UPDATE idempotency_key
                SET response_status = ?, response_body = ?
                WHERE workspace_id = ? AND key = ?
                """, status, body, workspaceId, key);
    }

    /**
     * Releases a claim so the request can genuinely be retried.
     *
     * <p>Used when the request failed in a way that says nothing about whether
     * retrying would fail again — a server error, or a connection that died
     * mid-flight. Recording a 500 and replaying it for 24 hours would turn one
     * bad minute into a bad day.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID workspaceId, String key) {
        jdbc.update("DELETE FROM idempotency_key WHERE workspace_id = ? AND key = ?",
                workspaceId, key);
    }

    /** For tests and diagnostics: how many keys a workspace holds. */
    @Transactional(readOnly = true)
    public long countFor(UUID workspaceId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_key WHERE workspace_id = ?",
                Long.class, workspaceId);
        return count == null ? 0 : count;
    }

    /** For tests: the recorded response, if any. */
    @Transactional(readOnly = true)
    public Optional<Recorded> recorded(UUID workspaceId, String key) {
        List<Recorded> rows = jdbc.query("""
                SELECT response_status, response_body
                FROM idempotency_key
                WHERE workspace_id = ? AND key = ? AND response_status IS NOT NULL
                """, (rs, n) -> new Recorded(rs.getInt(1), rs.getString(2)), workspaceId, key);
        return rows.stream().findFirst();
    }
}
