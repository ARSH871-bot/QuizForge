package com.quizforge.play.web;

import com.quizforge.api.InsightsApi;
import com.quizforge.api.model.QuestionStat;
import com.quizforge.api.model.QuestionStatList;
import com.quizforge.api.model.QuestionType;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.play.app.QuestionStatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Reports on how a tournament's questions went. For organisers, not players. */
@RestController
public class InsightController implements InsightsApi {

    private final QuestionStatsService stats;

    public InsightController(QuestionStatsService stats) {
        this.stats = stats;
    }

    @Override
    public ResponseEntity<QuestionStatList> getQuestionStats(String tournamentId, String workspaceHeader) {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }
        if (!principal.canView()) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "players cannot see how other players answered");
        }

        var rows = stats.forTournament(TypeId.parse("trn", tournamentId)).stream()
                .map(s -> {
                    QuestionStat row = new QuestionStat(TypeId.render("qst", s.questionId()),
                            QuestionType.fromValue(s.type()), s.prompt(),
                            s.shown(), s.answered(), s.correct());
                    row.setCorrectRate(s.correctRate());
                    return row;
                })
                .toList();
        return ResponseEntity.ok(new QuestionStatList(rows));
    }
}
