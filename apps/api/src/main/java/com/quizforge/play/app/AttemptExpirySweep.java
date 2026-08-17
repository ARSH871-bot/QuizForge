package com.quizforge.play.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Closes attempts abandoned past their time limit.
 *
 * <p>This job is <em>not</em> what enforces the time limit — {@link
 * com.quizforge.play.domain.Attempt#requireAnswerable} already refuses a late
 * answer on every access, regardless of whether this has run. A limit enforced
 * only by a background job would let a player submit late whenever the job was
 * behind.
 *
 * <p>Its only purpose is to settle leaderboards. Without it an abandoned
 * attempt stays {@code STARTED} forever and its standing never appears.
 */
@Component
public class AttemptExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(AttemptExpirySweep.class);

    /**
     * Arbitrary but fixed key for the Postgres advisory lock. Any instance
     * running the sweep asks for the same key; whoever loses simply skips this
     * tick rather than doing duplicate work.
     */
    private static final long LOCK_KEY = 8_474_263_001L;

    private final AttemptService attempts;
    private final JdbcTemplate jdbc;

    @Value("${quizforge.play.expiry-sweep-enabled:true}")
    private boolean enabled;

    public AttemptExpirySweep(AttemptService attempts, JdbcTemplate jdbc) {
        this.attempts = attempts;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${quizforge.play.expiry-sweep-interval-ms:60000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        run();
    }

    /**
     * Runs one sweep. Exposed separately from the schedule so a test can drive
     * it directly rather than waiting on a timer.
     *
     * @return how many attempts were closed
     */
    public int run() {
        if (!acquireLock()) {
            // Another instance is sweeping. Not an error, and not worth a log
            // line every minute.
            return 0;
        }

        try {
            List<UUID> overdue = attempts.overdueAttemptIds();
            int closed = 0;
            for (UUID attemptId : overdue) {
                try {
                    if (attempts.expireIfOverdue(attemptId)) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    // One bad attempt must not abort the sweep for the rest.
                    log.warn("Could not expire attempt {}", attemptId, e);
                }
            }
            if (closed > 0) {
                log.info("Expiry sweep closed {} abandoned attempt(s)", closed);
            }
            return closed;
        } finally {
            releaseLock();
        }
    }

    /**
     * A Postgres session-level advisory lock rather than a scheduling library.
     * The database is already a hard dependency and already the thing being
     * coordinated on; ShedLock would add a dependency and a table to solve a
     * problem one built-in function already solves.
     */
    private boolean acquireLock() {
        Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_lock(?)",
                Boolean.class, LOCK_KEY);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock() {
        try {
            jdbc.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
        } catch (RuntimeException e) {
            // The lock is session-scoped, so it is released when the connection
            // returns to the pool even if this fails.
            log.debug("Advisory lock release failed; it will lapse with the session", e);
        }
    }
}
