package com.quizforge.identity;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import com.quizforge.platform.id.UuidV7;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIsolationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void rowLevelSecurityIsEnabledOnTenantScopedTables() {
        var secured = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND rowsecurity",
                String.class);

        assertThat(secured).contains("api_key", "membership");
    }

    @Test
    void aPolicyExistsForEveryTenantScopedTable() {
        var policies = jdbc.queryForList(
                "SELECT tablename FROM pg_policies WHERE schemaname = 'public'",
                String.class);

        assertThat(policies).contains("api_key", "membership");
    }

    @Test
    void theApplicationRoleDoesNotBypassRls() {
        Boolean bypasses = jdbc.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'quizforge_app'",
                Boolean.class);

        assertThat(bypasses)
                .as("the application role must not bypass RLS, or the policies are decorative")
                .isFalse();
    }

    @Test
    void policiesActuallyHideAnotherWorkspacesRows() {
        // The decisive test. Checking that a policy *exists* proves nothing:
        // RLS is silently skipped for superusers, so a policy can be present
        // and enforce nothing at all. This inserts a row for one workspace and
        // asserts it is invisible while a different workspace is in scope.
        UUID accountId = UuidV7.generate();
        UUID workspaceA = UuidV7.generate();
        UUID workspaceB = UuidV7.generate();

        jdbc.update("INSERT INTO account (id, email, password_hash, display_name) "
                + "VALUES (?, ?, 'x', 'Test')", accountId, accountId + "@example.test");
        for (UUID ws : new UUID[]{workspaceA, workspaceB}) {
            jdbc.update("INSERT INTO workspace (id, name, slug, created_by) VALUES (?, ?, ?, ?)",
                    ws, "W", ws.toString(), accountId);
        }
        jdbc.update("INSERT INTO api_key (id, workspace_id, created_by, name, token_hash, "
                        + "last_four, environment) VALUES (?, ?, ?, 'k', ?, 'abcd', 'test')",
                UuidV7.generate(), workspaceA, accountId, UuidV7.generate().toString());

        Integer visibleFromB = jdbc.execute((ConnectionCallback<Integer>) connection -> {
            try (Statement s = connection.createStatement()) {
                // SET ROLE drops superuser status for this transaction, which is
                // what makes the policies apply at all.
                s.execute("BEGIN");
                s.execute("SET LOCAL ROLE quizforge_app");
                s.execute("SET LOCAL app.workspace_id = '" + workspaceB + "'");
                try (ResultSet rs = s.executeQuery(
                        "SELECT COUNT(*) FROM api_key WHERE workspace_id = '" + workspaceA + "'")) {
                    rs.next();
                    int count = rs.getInt(1);
                    s.execute("ROLLBACK");
                    return count;
                }
            }
        });

        assertThat(visibleFromB)
                .as("workspace A's api_key must be invisible while workspace B is in scope")
                .isZero();
    }

    @Test
    void theCurrentWorkspaceFunctionReadsTheTransactionSetting() {
        var result = jdbc.queryForObject(
                "SELECT set_config('app.workspace_id', "
                        + "'00000000-0000-0000-0000-000000000001', true) IS NOT NULL "
                        + "AND current_workspace_id() = "
                        + "'00000000-0000-0000-0000-000000000001'::uuid",
                Boolean.class);

        assertThat(result).isTrue();
    }
}
