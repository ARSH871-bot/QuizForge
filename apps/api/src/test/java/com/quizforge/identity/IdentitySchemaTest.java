package com.quizforge.identity;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class IdentitySchemaTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsIdentityTables() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("account", "workspace", "membership");
    }

    @Test
    void enforcesOneMembershipPerAccountPerWorkspace() {
        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'membership'::regclass",
                String.class);

        assertThat(constraints).contains("uk_membership_account_workspace");
    }

    @Test
    void storesEmailCaseInsensitivelyUnique() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'account'",
                String.class);

        assertThat(indexes).contains("uk_account_email_lower");
    }
}
