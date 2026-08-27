package com.quizforge.content.web;

import com.quizforge.api.QuestionsApi;
import com.quizforge.api.model.AuthorQuestionRequest;
import com.quizforge.api.model.Question;
import com.quizforge.api.model.QuestionPage;
import com.quizforge.api.model.QuestionType;
import com.quizforge.api.model.ReviseQuestionRequest;
import com.quizforge.content.app.QuestionService;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.web.PageWindow;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Questions — immutable and versioned.
 *
 * <p>{@code PATCH} does not modify the row it addresses. It inserts a new
 * version sharing the previous one's lineage and marks the previous row
 * superseded, leaving it byte-identical. That is what lets a tournament pin
 * exactly what a player saw: a revision published mid-tournament cannot
 * retroactively change a score.
 *
 * <p>Consequently an id here addresses a <em>version</em>, not a question. The
 * thing that persists across versions is {@code lineageId}.
 */
@RestController
public class QuestionController implements QuestionsApi {

    private final QuestionService questions;

    public QuestionController(QuestionService questions) {
        this.questions = questions;
    }

    @Override
    public ResponseEntity<QuestionPage> listQuestions(String bankId, String workspaceHeader,
                                                      Integer limit, String cursor) {
        requireWorkspace();
        PageWindow window = PageWindow.of(limit, cursor);

        var slice = window.slice(questions.currentIn(TypeId.parse("bnk", bankId), window),
                com.quizforge.content.domain.Question::getId);

        QuestionPage page = new QuestionPage(
                slice.data().stream().map(this::represent).toList());
        page.setNextCursor(slice.nextCursor());
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<Question> authorQuestion(String bankId, AuthorQuestionRequest request,
                                                   String workspaceHeader) {
        Principal principal = requireWorkspace();

        var authored = questions.author(
                TypeId.parse("bnk", bankId),
                principal.accountId(),
                domainType(request.getType()),
                request.getPrompt(),
                PayloadMapper.toDomain(request.getPayload()),
                request.getDifficulty());

        return ResponseEntity.status(HttpStatus.CREATED).body(represent(authored));
    }

    @Override
    public ResponseEntity<Question> getQuestion(String questionId, String workspaceHeader) {
        requireWorkspace();
        return ResponseEntity.ok(represent(
                questions.requireById(TypeId.parse("qst", questionId))));
    }

    /**
     * Returns the <em>new</em> version, whose id differs from the one in the
     * path. The path named the version being revised; this is its replacement.
     */
    @Override
    public ResponseEntity<Question> reviseQuestion(String questionId,
                                                   ReviseQuestionRequest request,
                                                   String workspaceHeader) {
        Principal principal = requireWorkspace();

        var revised = questions.revise(
                TypeId.parse("qst", questionId),
                principal.accountId(),
                request.getPrompt(),
                PayloadMapper.toDomain(request.getPayload()),
                request.getDifficulty());

        return ResponseEntity.ok(represent(revised));
    }

    @Override
    public ResponseEntity<QuestionPage> listQuestionVersions(String questionId,
                                                             String workspaceHeader,
                                                             Integer limit, String cursor) {
        requireWorkspace();
        PageWindow window = PageWindow.of(limit, cursor);

        // The one ascending page: a history reads forwards.
        var slice = window.slice(questions.versionsOf(TypeId.parse("qst", questionId), window),
                com.quizforge.content.domain.Question::getId);

        QuestionPage page = new QuestionPage(
                slice.data().stream().map(this::represent).toList());
        page.setNextCursor(slice.nextCursor());
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<Void> retireQuestion(String questionId, String workspaceHeader) {
        Principal principal = requireWorkspace();
        questions.retire(TypeId.parse("qst", questionId), principal.accountId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Carries the payload, which names the correct answer.
     *
     * <p>Correct here and forbidden during play: authoring requires
     * {@code EDITOR}, and an author is entitled to see what they wrote. The play
     * endpoints render a different shape from the same row for exactly this
     * reason.
     */
    private Question represent(com.quizforge.content.domain.Question question) {
        Question body = new Question(
                TypeId.render("qst", question.getId()),
                TypeId.render("bnk", question.getBankId()),
                TypeId.render("qst", question.getLineageId()),
                question.getVersion(),
                QuestionType.fromValue(question.getType().name()),
                question.getPrompt(),
                PayloadMapper.toApi(question.payload()));

        body.setSupersededBy(question.getSupersededBy() == null
                ? null
                : TypeId.render("qst", question.getSupersededBy()));
        body.setDifficulty(question.getDifficulty());
        body.setRetired(question.getRetiredAt() != null);
        return body;
    }

    private com.quizforge.content.domain.QuestionType domainType(QuestionType type) {
        if (type == null) {
            throw ApiException.invalid("a question needs a type");
        }
        return com.quizforge.content.domain.QuestionType.valueOf(type.getValue());
    }

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
