package com.quizforge.content.grading;

import com.quizforge.content.domain.Question;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class GraderRegistry {

    private final Map<QuestionType, QuestionGrader> byType = new EnumMap<>(QuestionType.class);

    public GraderRegistry(List<QuestionGrader> graders) {
        for (QuestionGrader grader : graders) {
            for (QuestionType type : grader.handles()) {
                byType.put(type, grader);
            }
        }
    }

    /** Grades against an entity, unpacking its stored payload. */
    public GradingResult grade(Question question, String given) {
        return grade(question.getType(), question.payload(), given);
    }

    public GradingResult grade(QuestionType type, Payload payload, String given) {
        QuestionGrader grader = byType.get(type);
        if (grader == null) {
            // Unreachable unless a type is added without a grader. Fail loudly
            // rather than silently marking every answer wrong.
            throw new ApiException(ErrorCode.INTERNAL, "no grader registered for " + type);
        }
        return grader.grade(payload, given);
    }
}
