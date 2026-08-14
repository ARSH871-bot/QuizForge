package com.quizforge.content.app;

import com.quizforge.content.QuestionAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Implements the published content API over the internal services. */
@Service
public class QuestionAccessAdapter implements QuestionAccess {

    private final QuestionService questions;
    private final QuestionBankService banks;

    public QuestionAccessAdapter(QuestionService questions, QuestionBankService banks) {
        this.questions = questions;
        this.banks = banks;
    }

    @Override
    @Transactional(readOnly = true)
    public int currentQuestionCount(UUID bankId) {
        return questions.currentIn(bankId).size();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean bankBelongsTo(UUID bankId, UUID workspaceId) {
        return banks.findById(bankId)
                .map(bank -> bank.getWorkspaceId().equals(workspaceId))
                .orElse(false);
    }
}
