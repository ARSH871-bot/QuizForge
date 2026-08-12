package com.quizforge.content.app;

import com.quizforge.content.domain.QuestionBank;
import com.quizforge.content.repo.QuestionBankRepository;
import com.quizforge.identity.WorkspaceAccess;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class QuestionBankService {

    private final QuestionBankRepository banks;
    private final WorkspaceAccess access;

    public QuestionBankService(QuestionBankRepository banks, WorkspaceAccess access) {
        this.banks = banks;
        this.access = access;
    }

    @Transactional
    public QuestionBank create(UUID workspaceId, UUID actorId, String name) {
        requireContentPermission(workspaceId, actorId);

        if (name == null || name.isBlank()) {
            throw ApiException.invalid("bank name is required");
        }
        if (banks.existsByWorkspaceIdAndName(workspaceId, name)) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "a bank with that name already exists in this workspace");
        }

        return banks.save(new QuestionBank(UuidV7.generate(), workspaceId, name, actorId));
    }

    @Transactional(readOnly = true)
    public QuestionBank requireById(UUID bankId) {
        return banks.findById(bankId)
                .orElseThrow(() -> ApiException.notFound("question bank"));
    }

    @Transactional(readOnly = true)
    public List<QuestionBank> activeIn(UUID workspaceId) {
        return banks.findByWorkspaceIdAndArchivedAtIsNull(workspaceId);
    }

    @Transactional
    public void archive(UUID bankId, UUID actorId) {
        QuestionBank bank = requireById(bankId);
        requireContentPermission(bank.getWorkspaceId(), actorId);
        bank.archive();
        banks.save(bank);
    }

    /**
     * Package-private so QuestionService can reuse the check without exposing
     * an authorization hook on the public API of this module.
     */
    void requireContentPermission(UUID workspaceId, UUID actorId) {
        if (!access.canManageContent(workspaceId, actorId)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "you do not have permission to manage content in this workspace");
        }
    }
}
