package com.quizforge.content.app;

import com.quizforge.content.domain.Question;
import com.quizforge.content.domain.QuestionBank;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.content.repo.QuestionRepository;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import com.quizforge.platform.web.PageWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class QuestionService {

    private final QuestionRepository questions;
    private final QuestionBankService banks;

    public QuestionService(QuestionRepository questions, QuestionBankService banks) {
        this.questions = questions;
        this.banks = banks;
    }

    /**
     * Writes version 1 of a new question. The id of the row becomes its own
     * lineage id, so a lineage needs no separate table.
     */
    @Transactional
    public Question author(UUID bankId, UUID actorId, QuestionType type, String prompt,
                           Payload payload, String difficulty) {
        QuestionBank bank = banks.requireById(bankId);
        banks.requireContentPermission(bank.getWorkspaceId(), actorId);

        validate(prompt, type, payload);

        String hash = ContentHash.of(type, prompt, payload);
        rejectDuplicate(bankId, hash);

        UUID id = UuidV7.generate();
        return questions.save(new Question(id, bankId, bank.getWorkspaceId(), id, 1,
                type, prompt, type.writePayload(payload), hash, difficulty, actorId));
    }

    /**
     * Writes the next version of an existing question and supersedes the
     * previous one. The previous row is otherwise untouched, which is what
     * lets a tournament pin exactly what a player saw.
     */
    @Transactional
    public Question revise(UUID questionId, UUID actorId, String prompt,
                           Payload payload, String difficulty) {
        Question current = requireById(questionId);

        if (current.getSupersededBy() != null) {
            throw ApiException.invalid(
                    "this version has already been superseded; revise the current one");
        }

        QuestionBank bank = banks.requireById(current.getBankId());
        banks.requireContentPermission(bank.getWorkspaceId(), actorId);

        validate(prompt, current.getType(), payload);

        String hash = ContentHash.of(current.getType(), prompt, payload);
        int nextVersion = (int) questions.countByLineageId(current.getLineageId()) + 1;

        Question next = questions.save(new Question(
                UuidV7.generate(), current.getBankId(), current.getWorkspaceId(),
                current.getLineageId(), nextVersion, current.getType(), prompt,
                current.getType().writePayload(payload), hash, difficulty, actorId));

        // Order matters: the replacement row must exist before the foreign key
        // on superseded_by can point at it.
        current.supersede(next.getId());
        questions.save(current);

        return next;
    }

    /** Any versions among the given ids; ids that do not resolve are absent. */
    @Transactional(readOnly = true)
    public List<Question> findAll(Collection<UUID> questionIds) {
        return questions.findAllById(questionIds);
    }

    @Transactional(readOnly = true)
    public Question requireById(UUID questionId) {
        return questions.findById(questionId)
                .orElseThrow(() -> ApiException.notFound("question"));
    }

    /** The current, non-retired version of every question in a bank. */
    /**
     * Every version of a question, oldest first.
     *
     * <p>Accepts any version's id, since they all carry the same lineage.
     * Exactly one of the returned versions has no {@code supersededBy}.
     */
    @Transactional(readOnly = true)
    public List<Question> versionsOf(UUID questionId) {
        return questions.findByLineageIdOrderByVersionAsc(requireById(questionId).getLineageId());
    }

    /** One keyset page of a bank's current questions, newest first. */
    @Transactional(readOnly = true)
    public List<Question> currentIn(UUID bankId, PageWindow window) {
        return window.after() == null
                ? questions.findByBankIdAndSupersededByIsNullAndRetiredAtIsNullOrderByIdDesc(
                        bankId, window.fetchSize())
                : questions
                        .findByBankIdAndSupersededByIsNullAndRetiredAtIsNullAndIdLessThanOrderByIdDesc(
                                bankId, window.after(), window.fetchSize());
    }

    /**
     * One keyset page of a lineage, oldest first.
     *
     * <p>The only ascending page in the API: a history reads forwards.
     */
    @Transactional(readOnly = true)
    public List<Question> versionsOf(UUID questionId, PageWindow window) {
        UUID lineageId = requireById(questionId).getLineageId();
        return window.after() == null
                ? questions.findByLineageIdOrderByIdAsc(lineageId, window.fetchSize())
                : questions.findByLineageIdAndIdGreaterThanOrderByIdAsc(
                        lineageId, window.after(), window.fetchSize());
    }

    @Transactional(readOnly = true)
    public List<Question> currentIn(UUID bankId) {
        return questions.findByBankIdAndSupersededByIsNullAndRetiredAtIsNull(bankId);
    }

    @Transactional
    public void retire(UUID questionId, UUID actorId) {
        Question question = requireById(questionId);
        QuestionBank bank = banks.requireById(question.getBankId());
        banks.requireContentPermission(bank.getWorkspaceId(), actorId);
        question.retire();
        questions.save(question);
    }

    private void validate(String prompt, QuestionType type, Payload payload) {
        if (prompt == null || prompt.isBlank()) {
            throw ApiException.invalid("a question needs a prompt");
        }
        if (payload == null) {
            throw ApiException.invalid("a question needs a payload");
        }
        payload.validate(type);
    }

    private void rejectDuplicate(UUID bankId, String hash) {
        questions.findByBankIdAndContentHashAndSupersededByIsNull(bankId, hash)
                .ifPresent(existing -> {
                    throw new ApiException(ErrorCode.ALREADY_EXISTS,
                            "an identical question already exists in this bank");
                });
    }
}
