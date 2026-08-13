package com.quizforge.content;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSchemaTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void createsContentTables() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("question_bank", "question");
    }

    @Test
    void enforcesOneVersionPerLineage() {
        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'question'::regclass",
                String.class);

        assertThat(constraints).contains("uk_question_lineage_version");
    }

    @Test
    void constrainsQuestionTypeToKnownValues() {
        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'question'::regclass",
                String.class);

        assertThat(constraints).contains("ck_question_type");
    }

    @Test
    void indexesTheCurrentVersionOfEachLineage() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'question'",
                String.class);

        assertThat(indexes).contains("idx_question_current");
    }
}
