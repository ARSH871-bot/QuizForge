package com.quizforge.platform.tenancy;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start if the database login role cannot bypass row-level security.
 *
 * <p>Every tenant table forces RLS, and tenant-scoped work assumes
 * {@code quizforge_app} per transaction. Work with no workspace in scope —
 * registering, creating a workspace, joining from a share link, the expiry
 * sweep — runs as the login role and relies on it bypassing RLS. A superuser
 * does; that is what local Docker and Testcontainers provide, so no test can
 * notice when a production database does not.
 *
 * <p>Managed Postgres usually hands out a non-superuser login. Without this
 * check the application starts, serves reads, and then fails on the first
 * sign-up with a policy violation. Failing at boot names the actual fix.
 */
@Component
public class LoginRoleCheck implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public LoginRoleCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Boolean bypasses = jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                Boolean.class);
        if (!Boolean.TRUE.equals(bypasses)) {
            String role = jdbc.queryForObject("SELECT current_user", String.class);
            throw new IllegalStateException(
                    "Database login role '" + role + "' cannot bypass row-level security. "
                    + "Account-scoped writes (sign-up, workspace creation, joining a "
                    + "tournament) would fail. Grant it: ALTER ROLE " + role + " BYPASSRLS;");
        }
    }
}
