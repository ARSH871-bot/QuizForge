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

        assertThat(secured).contains("api_key", "membership", "audit_event");
    }

    @Test
    void aPolicyExistsForEveryTenantScopedTable() {
        var policies = jdbc.queryForList(
                "SELECT tablename FROM pg_policies WHERE schemaname = 'public'",
                String.class);

        assertThat(policies).contains("api_key", "membership", "audit_event");
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
    void policiesActuallyHideAttemptChildrenAndAuditRows() {
        UUID accountId = UuidV7.generate();
        UUID workspaceA = UuidV7.generate();
        UUID workspaceB = UuidV7.generate();
        UUID bankId = UuidV7.generate();
        UUID questionId = UuidV7.generate();
        UUID tournamentId = UuidV7.generate();
        UUID attemptId = UuidV7.generate();
        UUID responseId = UuidV7.generate();
        UUID auditId = UuidV7.generate();

        jdbc.update("INSERT INTO account (id, email, password_hash, display_name) "
                + "VALUES (?, ?, 'x', 'Test')", accountId, accountId + "@example.test");
        for (UUID ws : new UUID[]{workspaceA, workspaceB}) {
            jdbc.update("INSERT INTO workspace (id, name, slug, created_by) VALUES (?, ?, ?, ?)",
                    ws, "W", ws.toString(), accountId);
        }
        jdbc.update("INSERT INTO question_bank (id, workspace_id, name, created_by) "
                + "VALUES (?, ?, 'Bank', ?)", bankId, workspaceA, accountId);
        jdbc.update("INSERT INTO question (id, bank_id, workspace_id, lineage_id, version, "
                        + "type, prompt, payload, content_hash, difficulty, created_by) "
                        + "VALUES (?, ?, ?, ?, 1, 'SINGLE_CHOICE', 'Prompt', "
                        + "'{\"kind\":\"choice\",\"options\":[{\"text\":\"A\",\"correct\":true},"
                        + "{\"text\":\"B\",\"correct\":false}]}'::jsonb, ?, 'EASY', ?)",
                questionId, bankId, workspaceA, questionId, questionId.toString(), accountId);
        jdbc.update("INSERT INTO tournament (id, workspace_id, bank_id, name, slug, opens_at, "
                        + "closes_at, question_count, created_by) "
                        + "VALUES (?, ?, ?, 'Weekly', ?, now() - interval '1 hour', "
                        + "now() + interval '1 hour', 1, ?)",
                tournamentId, workspaceA, bankId, tournamentId.toString(), accountId);
        jdbc.update("INSERT INTO attempt (id, tournament_id, workspace_id, account_id, "
                        + "score_denominator) VALUES (?, ?, ?, ?, 1)",
                attemptId, tournamentId, workspaceA, accountId);
        jdbc.update("INSERT INTO attempt_question (attempt_id, position, question_id, option_order) "
                        + "VALUES (?, 1, ?, '[]'::jsonb)",
                attemptId, questionId);
        jdbc.update("INSERT INTO response (id, attempt_id, question_id, given, correct) "
                        + "VALUES (?, ?, ?, 'A', true)",
                responseId, attemptId, questionId);
        jdbc.update("INSERT INTO audit_event (id, workspace_id, actor_id, action) "
                        + "VALUES (?, ?, ?, 'test.event')",
                auditId, workspaceA, accountId);

        String attemptQuestionCount =
                "SELECT COUNT(*) FROM attempt_question WHERE attempt_id = '" + attemptId + "'";
        String responseCount =
                "SELECT COUNT(*) FROM response WHERE attempt_id = '" + attemptId + "'";
        String auditCount =
                "SELECT COUNT(*) FROM audit_event WHERE workspace_id = '" + workspaceA + "'";

        assertThat(countAsApp(workspaceA, attemptQuestionCount)).isEqualTo(1);
        assertThat(countAsApp(workspaceA, responseCount)).isEqualTo(1);
        assertThat(countAsApp(workspaceA, auditCount)).isEqualTo(1);

        assertThat(countAsApp(workspaceB, attemptQuestionCount))
                .as("attempt_question must inherit tenant scope from attempt")
                .isZero();
        assertThat(countAsApp(workspaceB, responseCount))
                .as("response must inherit tenant scope from attempt")
                .isZero();
        assertThat(countAsApp(workspaceB, auditCount))
                .as("audit_event must be tenant-scoped directly")
                .isZero();
    }

    private Integer countAsApp(UUID workspaceId, String sql) {
        return jdbc.execute((ConnectionCallback<Integer>) connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("BEGIN");
                s.execute("SET LOCAL ROLE quizforge_app");
                s.execute("SET LOCAL app.workspace_id = '" + workspaceId + "'");
                try (ResultSet rs = s.executeQuery(sql)) {
                    rs.next();
                    int count = rs.getInt(1);
                    s.execute("ROLLBACK");
                    return count;
                }
            }
        });
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
