package com.quizforge.content;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.repo.QuestionBankRepository;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves content is isolated by PostgreSQL, not merely that a policy exists.
 * RLS is silently skipped for superusers, so a policy can be present and
 * enforce nothing at all.
 */
class ContentIsolationTest extends AbstractIntegrationTest {

    @Autowired private QuestionBankRepository bankRepository;
    @Autowired private QuestionBankService banks;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;
    @Autowired private TransactionTemplate transactions;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void aWorkspaceCannotSeeAnotherWorkspacesBanks() {
        UUID ownerA = newAccount();
        var workspaceA = workspaces.create(ownerA, "Acme " + UUID.randomUUID());
        TenantContext.set(workspaceA.getId());
        banks.create(workspaceA.getId(), ownerA, "Geography");
        TenantContext.clear();

        UUID ownerB = newAccount();
        var workspaceB = workspaces.create(ownerB, "Globex " + UUID.randomUUID());

        TenantContext.set(workspaceA.getId());
        long visibleFromA = transactions.execute(status ->
                (long) bankRepository.findByWorkspaceIdAndArchivedAtIsNull(
                        workspaceA.getId()).size());
        TenantContext.clear();

        TenantContext.set(workspaceB.getId());
        long visibleFromB = transactions.execute(status ->
                (long) bankRepository.findByWorkspaceIdAndArchivedAtIsNull(
                        workspaceA.getId()).size());
        TenantContext.clear();

        assertThat(visibleFromA).as("workspace A must see its own bank").isEqualTo(1);
        assertThat(visibleFromB)
                .as("workspace B must not see workspace A's bank, even when asking for it")
                .isZero();
    }
}
