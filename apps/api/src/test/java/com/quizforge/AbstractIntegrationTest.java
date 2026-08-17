package com.quizforge;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests. Starts one PostgreSQL container per JVM
 * (the container is static, so it is reused across every test class) and
 * points Spring at it. Flyway runs against the real database on context
 * startup, so subclasses test against the schema that production will have.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("quizforge")
                    .withUsername("quizforge")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mail.username", () -> "test@example.invalid");
        registry.add("spring.mail.password", () -> "unused");
        registry.add("email.test.mode", () -> "true");
        registry.add("quizforge.opentdb.bootstrap-enabled", () -> "false");
        // The sweep is driven explicitly in its own test. Left on a timer it
        // would close attempts mid-assertion in every other test.
        registry.add("quizforge.play.expiry-sweep-enabled", () -> "false");
    }
}
