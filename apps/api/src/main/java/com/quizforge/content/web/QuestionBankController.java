package com.quizforge.content.web;

import com.quizforge.api.QuestionBanksApi;
import com.quizforge.api.model.CreateQuestionBankRequest;
import com.quizforge.api.model.QuestionBank;
import com.quizforge.api.model.QuestionBankPage;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Question banks.
 *
 * <p>The first HTTP surface the {@code content} module has ever had. Its
 * services have been complete since M2 and unreachable ever since, so a
 * workspace could be created but never filled.
 */
@RestController
public class QuestionBankController implements QuestionBanksApi {

    private final QuestionBankService banks;

    public QuestionBankController(QuestionBankService banks) {
        this.banks = banks;
    }

    @Override
    public ResponseEntity<QuestionBankPage> listQuestionBanks(String workspaceHeader) {
        Principal principal = requireWorkspace();

        List<QuestionBank> data = banks.activeIn(principal.workspaceId()).stream()
                .map(this::represent)
                .sorted(Comparator.comparing(QuestionBank::getName))
                .toList();

        QuestionBankPage page = new QuestionBankPage(data);
        page.setNextCursor(null);
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<QuestionBank> createQuestionBank(CreateQuestionBankRequest request,
                                                           String workspaceHeader) {
        Principal principal = requireWorkspace();
        var created = banks.create(principal.workspaceId(), principal.accountId(),
                request.getName(), request.getDescription());

        return ResponseEntity.status(HttpStatus.CREATED).body(represent(created));
    }

    /**
     * Reads a bank whether or not it is archived, unlike the listing. A client
     * following an id it already holds deserves an answer rather than a 404
     * that reads like deletion.
     */
    @Override
    public ResponseEntity<QuestionBank> getQuestionBank(String bankId, String workspaceHeader) {
        requireWorkspace();
        return ResponseEntity.ok(represent(banks.requireById(TypeId.parse("bnk", bankId))));
    }

    @Override
    public ResponseEntity<Void> archiveQuestionBank(String bankId, String workspaceHeader) {
        Principal principal = requireWorkspace();
        banks.archive(TypeId.parse("bnk", bankId), principal.accountId());
        return ResponseEntity.noContent().build();
    }

    private QuestionBank represent(com.quizforge.content.domain.QuestionBank bank) {
        QuestionBank body = new QuestionBank(
                TypeId.render("bnk", bank.getId()), bank.getName());
        body.setDescription(bank.getDescription());
        return body;
    }

    /**
     * Tenant isolation for content rests on Row-Level Security, which only
     * engages once a workspace is in scope. {@code WorkspaceScopeFilter} refuses
     * the request before it reaches here, but the check is repeated so this
     * class is correct on its own terms rather than by depending on filter
     * ordering.
     */
    private Principal requireWorkspace() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }
        if (principal.accountId() == null) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "authoring requires a signed-in account, not an API key");
        }
        return principal;
    }
}
