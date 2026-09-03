package com.quizforge.play;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PlaySchemaTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void createsPlayTables() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("attempt", "attempt_question", "response");
    }

    @Test
    void answeringTheSameQuestionTwiceIsPreventedByTheDatabase() {
        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'response'::regclass",
                String.class);

        assertThat(constraints).contains("uk_response_attempt_question");
    }

    @Test
    void aScoreCannotExceedTheQuestionsAsked() {
        // Enforced by the database as well as the domain. The domain check can
        // be bypassed by a direct write; this cannot.
        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'attempt'::regclass",
                String.class);

        assertThat(constraints).contains("ck_attempt_numerator", "ck_attempt_denominator");
    }

    @Test
    void attemptsAreTenantIsolated() {
        var secured = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND rowsecurity",
                String.class);

        assertThat(secured).contains("attempt", "attempt_question", "response");
    }

    @Test
    void theExpirySweepHasAnIndexToUse() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'attempt'",
                String.class);

        assertThat(indexes).contains("idx_attempt_open_expiring");
    }
}
