package com.quizforge.content.app;

import com.quizforge.content.QuestionAccess;
import com.quizforge.content.domain.Question;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.grading.GraderRegistry;
import com.quizforge.platform.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/** Implements the published content API over the internal services. */
@Service
public class QuestionAccessAdapter implements QuestionAccess {

    private final QuestionService questions;
    private final QuestionBankService banks;
    private final GraderRegistry graders;

    public QuestionAccessAdapter(QuestionService questions, QuestionBankService banks,
                                 GraderRegistry graders) {
        this.questions = questions;
        this.banks = banks;
        this.graders = graders;
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

    @Override
    @Transactional(readOnly = true)
    public List<UUID> selectQuestions(UUID bankId, int count, long seed) {
        List<Question> available = new ArrayList<>(questions.currentIn(bankId));
        if (available.size() < count) {
            throw ApiException.invalid("the bank holds only " + available.size()
                    + " questions, but " + count + " were requested");
        }

        // Sorted before shuffling so the seed alone determines the outcome.
        // Without this the database's row order would leak into the result and
        // an attempt could not be reconstructed.
        available.sort((a, b) -> a.getId().compareTo(b.getId()));
        Collections.shuffle(available, new Random(seed));

        return available.subList(0, count).stream().map(Question::getId).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int optionCount(UUID questionId) {
        Question question = questions.requireById(questionId);
        return question.payload() instanceof ChoicePayload choice ? choice.options().size() : 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, QuestionSummary> describe(Collection<UUID> questionIds) {
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        return questions.findAll(questionIds).stream().collect(Collectors.toMap(
                Question::getId, q -> new QuestionSummary(q.getType().name(), q.getPrompt())));
    }

    @Override
    @Transactional(readOnly = true)
    public PlayableQuestion playable(UUID questionId, List<Integer> optionOrder) {
        Question question = questions.requireById(questionId);

        List<String> options = List.of();
        if (question.payload() instanceof ChoicePayload choice) {
            List<String> canonical = choice.options().stream()
                    .map(ChoicePayload.Option::text)
                    .toList();

            // An order that does not match the question is ignored rather than
            // fatal: a stale shuffle must not make a question unplayable.
            boolean usable = optionOrder != null
                    && optionOrder.size() == canonical.size()
                    && optionOrder.stream().allMatch(i -> i >= 0 && i < canonical.size())
                    && optionOrder.stream().distinct().count() == canonical.size();

            options = usable
                    ? optionOrder.stream().map(canonical::get).toList()
                    : canonical;
        }

        return new PlayableQuestion(question.getId(), question.getType().name(),
                question.getPrompt(), options);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean grade(UUID questionId, String given) {
        Question question = questions.requireById(questionId);
        return graders.grade(question, given).correct();
    }
}
