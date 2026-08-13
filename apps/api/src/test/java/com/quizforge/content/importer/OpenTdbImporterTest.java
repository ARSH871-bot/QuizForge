package com.quizforge.content.importer;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the OpenTDB mapping against a fake client. No network call is made:
 * a test that depends on a third-party API fails for reasons unrelated to the
 * change being tested.
 */
@Import(OpenTdbImporterTest.FakeClientConfig.class)
class OpenTdbImporterTest extends AbstractIntegrationTest {

    /** Records what was asked for, and returns whatever the test queued. */
    static class FakeOpenTdbClient implements OpenTdbClient {
        final List<String> requests = new ArrayList<>();
        List<OpenTdbQuestion> next = List.of();
        RuntimeException failWith;

        @Override
        public List<OpenTdbQuestion> fetch(Integer categoryId, String difficulty, int amount) {
            requests.add(categoryId + ":" + difficulty + ":" + amount);
            if (failWith != null) {
                throw failWith;
            }
            return next;
        }
    }

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        FakeOpenTdbClient fakeOpenTdbClient() {
            return new FakeOpenTdbClient();
        }
    }

    @Autowired private OpenTdbImporter importer;
    @Autowired private FakeOpenTdbClient client;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    @AfterEach
    void reset() {
        TenantContext.clear();
        client.next = List.of();
        client.failWith = null;
        client.requests.clear();
    }

    private record Fixture(UUID actor, UUID bankId) {
    }

    private Fixture fixture() {
        UUID owner = accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
        var workspace = workspaces.create(owner, "Acme " + UUID.randomUUID());
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Trivia");
        return new Fixture(owner, bank.getId());
    }

    private static OpenTdbClient.OpenTdbQuestion multiple() {
        return new OpenTdbClient.OpenTdbQuestion("multiple", "easy",
                "What is the capital of France?", "Paris", List.of("Lyon", "Nice", "Marseille"));
    }

    private static OpenTdbClient.OpenTdbQuestion booleanQuestion() {
        return new OpenTdbClient.OpenTdbQuestion("boolean", "hard",
                "The Earth is flat.", "False", List.of("True"));
    }

    @Test
    void mapsMultipleToSingleChoiceAndBooleanToTrueFalse() {
        var f = fixture();
        client.next = List.of(multiple(), booleanQuestion());

        var report = importer.importInto(f.bankId(), f.actor(), "9:easy:10");

        assertThat(report.imported()).isEqualTo(2);
        assertThat(questions.currentIn(f.bankId()))
                .extracting(q -> q.getType())
                .containsExactlyInAnyOrder(QuestionType.SINGLE_CHOICE, QuestionType.TRUE_FALSE);
    }

    @Test
    void marksExactlyOneOptionCorrect() {
        var f = fixture();
        client.next = List.of(multiple());

        importer.importInto(f.bankId(), f.actor(), "9:easy:1");

        var question = questions.currentIn(f.bankId()).get(0);
        var payload = (ChoicePayload) question.payload();

        assertThat(payload.options()).hasSize(4);
        assertThat(payload.options()).filteredOn(ChoicePayload.Option::correct)
                .extracting(ChoicePayload.Option::text)
                .containsExactly("Paris");
    }

    @Test
    void parsesTheSourceSpecification() {
        var f = fixture();
        client.next = List.of();

        importer.importInto(f.bankId(), f.actor(), "17:hard:25");

        assertThat(client.requests).containsExactly("17:hard:25");
    }

    @Test
    void reimportingTheSameQuestionsSkipsRatherThanFails() {
        var f = fixture();
        client.next = List.of(multiple(), booleanQuestion());

        importer.importInto(f.bankId(), f.actor(), "9:easy:10");
        var second = importer.importInto(f.bankId(), f.actor(), "9:easy:10");

        assertThat(second.imported()).isZero();
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(second.failed()).isZero();
    }

    @Test
    void reportsAnUnsupportedTypeWithoutAbandoningTheRest() {
        var f = fixture();
        client.next = List.of(
                multiple(),
                new OpenTdbClient.OpenTdbQuestion("crossword", "easy", "Odd one out?", "a",
                        List.of("b")));

        var report = importer.importInto(f.bankId(), f.actor(), "9:easy:10");

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.messages().get(0)).contains("crossword");
    }

    @Test
    void rejectsAMalformedSourceSpecification() {
        var f = fixture();

        assertThatThrownBy(() -> importer.importInto(f.bankId(), f.actor(), "nine:easy:10"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("categoryId:difficulty:amount");

        assertThatThrownBy(() -> importer.importInto(f.bankId(), f.actor(), "9:easy:500"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("between 1 and 50");
    }
}
