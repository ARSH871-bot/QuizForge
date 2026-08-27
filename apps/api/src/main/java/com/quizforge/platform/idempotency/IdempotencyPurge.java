package com.quizforge.platform.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes idempotency keys past their replay window.
 *
 * <p>Without this the table only ever grows, and a table that only grows is a
 * table that eventually becomes an incident. The window is what the contract
 * promises; a key older than it is no longer honoured, so keeping the row buys
 * nothing.
 *
 * <p>Coordinated by a Postgres advisory lock, the same way the attempt expiry
 * sweep is, so several instances can run this and only one does the work.
 */
@Component
public class IdempotencyPurge {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurge.class);

    /**
     * Fixed key for the advisory lock. Arbitrary, but distinct from the attempt
     * sweep's — two jobs sharing a lock key would silently serialise, each
     * skipping ticks because the other held it.
     *
     * <p>Decimal, and adjacent to the sweep's, so the two read as a pair. A long
     * hex literal would look enough like a credential to trip secret scanning,
     * which is a poor reason to make a reader wonder whether it is one.
     */
    private static final long LOCK_KEY = 8_474_263_002L;

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public IdempotencyPurge(JdbcTemplate jdbc,
                            @Value("${quizforge.idempotency.purge-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${quizforge.idempotency.purge-interval-ms:900000}")
    public void scheduled() {
        if (enabled) {
            run();
        }
    }

    /**
     * Deletes expired keys and reports how many.
     *
     * <p>Runs as the owning role rather than a tenant: it is a maintenance job
     * over every workspace, and there is no tenant to scope it to.
     */
    @Transactional
    public int run() {
        if (!acquire()) {
            return 0;
        }

        try {
            int removed = jdbc.update(
                    "DELETE FROM idempotency_key WHERE created_at < now() - make_interval(hours => ?)",
                    IdempotencyStore.REPLAY_WINDOW_HOURS);

            if (removed > 0) {
                log.info("Purged {} expired idempotency keys", removed);
            }
            return removed;
        } finally {
            release();
        }
    }

    private boolean acquire() {
        Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_lock(?)",
                Boolean.class, LOCK_KEY);
        return Boolean.TRUE.equals(acquired);
    }

    private void release() {
        try {
            jdbc.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
        } catch (RuntimeException e) {
            // Session-scoped, so it lapses with the connection regardless.
            log.debug("Advisory lock release failed; it will lapse with the session", e);
        }
    }
}
