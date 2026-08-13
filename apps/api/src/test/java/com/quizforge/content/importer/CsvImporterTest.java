package com.quizforge.content.importer;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CsvImporterTest extends AbstractIntegrationTest {

    @Autowired private CsvImporter importer;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static final String CSV = """
            type,prompt,options,correct,difficulty
            SINGLE_CHOICE,What is the capital of France?,Paris|Lyon|Nice,Paris,EASY
            TRUE_FALSE,The Earth is flat.,True|False,False,EASY
            NUMERIC,How many continents are there?,,7,MEDIUM
            SHORT_TEXT,Who wrote Hamlet?,,Shakespeare|William Shakespeare,MEDIUM
            """;

    private record Fixture(UUID actor, UUID bankId) {
    }

    private Fixture fixture() {
        UUID owner = accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
        var workspace = workspaces.create(owner, "Acme " + UUID.randomUUID());
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Imported");
        return new Fixture(owner, bank.getId());
    }

    @Test
    void importsEveryValidRow() {
        var f = fixture();

        var report = importer.importInto(f.bankId(), f.actor(), CSV);

        assertThat(report.imported()).isEqualTo(4);
        assertThat(report.failed()).isZero();
        assertThat(questions.currentIn(f.bankId())).hasSize(4);
    }

    @Test
    void mapsEachTypeCorrectly() {
        var f = fixture();
        importer.importInto(f.bankId(), f.actor(), CSV);

        var types = questions.currentIn(f.bankId()).stream()
                .map(q -> q.getType()).toList();

        assertThat(types).containsExactlyInAnyOrder(
                QuestionType.SINGLE_CHOICE, QuestionType.TRUE_FALSE,
                QuestionType.NUMERIC, QuestionType.SHORT_TEXT);
    }

    @Test
    void skipsDuplicatesRatherThanFailing() {
        var f = fixture();

        importer.importInto(f.bankId(), f.actor(), CSV);
        var second = importer.importInto(f.bankId(), f.actor(), CSV);

        assertThat(second.imported()).isZero();
        assertThat(second.skipped()).isEqualTo(4);
        assertThat(second.failed()).isZero();
        assertThat(questions.currentIn(f.bankId())).hasSize(4);
    }

    @Test
    void reportsBadRowsWithoutAbandoningGoodOnes() {
        var f = fixture();

        String mixed = """
                type,prompt,options,correct,difficulty
                SINGLE_CHOICE,Good question?,A|B,A,EASY
                NONSENSE_TYPE,Bad question?,A|B,A,EASY
                SINGLE_CHOICE,Missing answer?,A|B,,EASY
                """;

        var report = importer.importInto(f.bankId(), f.actor(), mixed);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(2);
        assertThat(report.messages()).hasSize(2);
        // 1-indexed by file line including the header, so the numbers match
        // what a human sees in a spreadsheet.
        assertThat(report.messages().get(0)).contains("row 3");
        assertThat(report.messages().get(1)).contains("row 4");
        assertThat(questions.currentIn(f.bankId())).hasSize(1);
    }

    @Test
    void rejectsACorrectAnswerThatIsNotAmongTheOptions() {
        var f = fixture();

        String bad = """
                type,prompt,options,correct,difficulty
                SINGLE_CHOICE,Capital of France?,Paris|Lyon,Berlin,EASY
                """;

        var report = importer.importInto(f.bankId(), f.actor(), bad);

        assertThat(report.imported()).isZero();
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.messages().get(0)).containsIgnoringCase("not among the options");
    }

    @Test
    void toleratesAnEmptyFile() {
        var f = fixture();

        var report = importer.importInto(f.bankId(), f.actor(), "type,prompt,options,correct,difficulty\n");

        assertThat(report.imported()).isZero();
        assertThat(report.failed()).isZero();
    }
}
