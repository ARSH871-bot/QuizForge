package com.quizforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void everyLegacyTableIsGone() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables)
                .as("V11 drops the coursework schema; nothing should reference it")
                .doesNotContain("users", "quiz", "legacy_question", "question_options",
                        "score", "participation", "quiz_likes", "categories");
    }

    @Test
    void flywayAppliesEveryMigration() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);

        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void migrationsCreateEveryExpectedTable() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables)
                .as("the current schema, after V11 dropped the legacy coursework tables")
                .contains("account", "workspace", "membership", "session", "api_key",
                        "audit_event", "question_bank", "question",
                        "tournament", "attempt", "attempt_question", "response", "standing");
    }
}
