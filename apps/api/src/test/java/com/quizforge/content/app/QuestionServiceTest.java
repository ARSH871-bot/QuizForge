package com.quizforge.content.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionServiceTest extends AbstractIntegrationTest {

    @Autowired private QuestionService questions;
    @Autowired private QuestionBankService banks;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    private static ChoicePayload capitalOfFrance() {
        return new ChoicePayload(List.of(
                new ChoicePayload.Option("Paris", true),
                new ChoicePayload.Option("Lyon", false)));
    }

    private record Fixture(UUID owner, UUID workspaceId, UUID bankId) {
    }

    private Fixture fixture() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme " + UUID.randomUUID());
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");
        return new Fixture(owner, workspace.getId(), bank.getId());
    }

    @Test
    void authorsAQuestionAtVersionOne() {
        var f = fixture();

        var question = questions.author(f.bankId(), f.owner(), QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");

        assertThat(question.getVersion()).isEqualTo(1);
        assertThat(question.getLineageId()).isEqualTo(question.getId());
        assertThat(question.getSupersededBy()).isNull();
    }

    @Test
    void revisingCreatesANewVersionAndLeavesTheOriginalIntact() {
        var f = fixture();
        var first = questions.author(f.bankId(), f.owner(), QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");

        var second = questions.revise(first.getId(), f.owner(),
                "Which city is the capital of France?", capitalOfFrance(), "EASY");

        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(second.getLineageId()).isEqualTo(first.getLineageId());
        assertThat(second.getId()).isNotEqualTo(first.getId());

        var original = questions.requireById(first.getId());
        assertThat(original.getPrompt()).isEqualTo("What is the capital of France?");
        assertThat(original.getSupersededBy()).isEqualTo(second.getId());
    }

    @Test
    void currentListingExcludesSupersededVersions() {
        var f = fixture();
        var first = questions.author(f.bankId(), f.owner(), QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");
        questions.revise(first.getId(), f.owner(),
                "Which city is the capital of France?", capitalOfFrance(), "EASY");

        var current = questions.currentIn(f.bankId());

        assertThat(current).hasSize(1);
        assertThat(current.get(0).getVersion()).isEqualTo(2);
    }

    @Test
    void rejectsAPayloadThatCannotBeGraded() {
        var f = fixture();
        var noCorrectAnswer = new ChoicePayload(List.of(
                new ChoicePayload.Option("Paris", false),
                new ChoicePayload.Option("Lyon", false)));

        assertThatThrownBy(() -> questions.author(f.bankId(), f.owner(),
                QuestionType.SINGLE_CHOICE, "Capital?", noCorrectAnswer, "EASY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void viewersCannotAuthor() {
        var f = fixture();
        UUID viewer = newAccount();
        workspaces.addMember(f.workspaceId(), f.owner(), viewer, Role.VIEWER);

        assertThatThrownBy(() -> questions.author(f.bankId(), viewer,
                QuestionType.SINGLE_CHOICE, "Capital?", capitalOfFrance(), "EASY"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    void rejectsADuplicateQuestionInTheSameBank() {
        var f = fixture();
        questions.author(f.bankId(), f.owner(), QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");

        // Same content, different whitespace and casing - the hash normalises.
        assertThatThrownBy(() -> questions.author(f.bankId(), f.owner(),
                QuestionType.SINGLE_CHOICE, "  What is the CAPITAL of France?  ",
                capitalOfFrance(), "EASY"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.ALREADY_EXISTS));
    }

    @Test
    void theSameQuestionMayLiveInTwoBanks() {
        var f = fixture();
        var other = banks.create(f.workspaceId(), f.owner(), "Trivia Night");

        questions.author(f.bankId(), f.owner(), QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");
        var duplicate = questions.author(other.getId(), f.owner(), QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");

        assertThat(duplicate.getId()).isNotNull();
    }
}
