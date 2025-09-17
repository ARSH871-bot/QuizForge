package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.models.Question;
import cs.quizzapp.prokect.backend.services.QuestionService;
import cs.quizzapp.prokect.backend.services.QuizService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quiz-questions")
@CrossOrigin(origins = "http://localhost:3000")
public class QuestionPaginationController {

    @Autowired
    private QuestionService questionService;

    @Autowired
    private QuizService quizService;

    /**
     * RUBRIC REQUIREMENT: "Questions must be presented on separate pages"
     */
    @GetMapping("/quiz/{quizId}/question/{questionNumber}")
    public ResponseEntity<?> getQuestionByNumber(@PathVariable Long quizId,
                                                 @PathVariable Integer questionNumber,
                                                 @RequestParam Long userId) {
        try {
            // Validate question number (1-10)
            if (questionNumber < 1 || questionNumber > 10) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Question number must be between 1 and 10");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            // Get all questions for the quiz
            List<Question> questions = questionService.getQuestionsByQuizId(quizId);
            if (questions.isEmpty() || questionNumber > questions.size()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Question not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            // Get the specific question (convert to 0-based indexing)
            Question question = questions.get(questionNumber - 1);

            // Create response without correct answer (for security)
            Map<String, Object> response = new HashMap<>();
            response.put("questionId", question.getId());
            response.put("questionNumber", questionNumber);
            response.put("questionText", question.getQuestionText());
            response.put("options", question.getOptions());
            response.put("totalQuestions", Math.min(questions.size(), 10));
            response.put("quizId", quizId);
            response.put("hasNext", questionNumber < Math.min(questions.size(), 10));
            response.put("hasPrevious", questionNumber > 1);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve question: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * RUBRIC REQUIREMENT: "Display appropriate feedback for correct & incorrect answers"
     * RUBRIC REQUIREMENT: "If a question is answered incorrectly, display the correct answer"
     */
    @PostMapping("/quiz/{quizId}/question/{questionNumber}/answer")
    public ResponseEntity<?> submitQuestionAnswer(@PathVariable Long quizId,
                                                  @PathVariable Integer questionNumber,
                                                  @RequestParam Long userId,
                                                  @RequestBody Map<String, String> answerData) {
        try {
            String userAnswer = answerData.get("answer");
            if (userAnswer == null || userAnswer.trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Answer is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            // Get questions
            List<Question> questions = questionService.getQuestionsByQuizId(quizId);
            if (questions.isEmpty() || questionNumber > questions.size()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Question not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            Question question = questions.get(questionNumber - 1);
            boolean isCorrect = question.getCorrectAnswer().equalsIgnoreCase(userAnswer.trim());

            // Create feedback response
            Map<String, Object> feedback = new HashMap<>();
            feedback.put("questionId", question.getId());
            feedback.put("questionNumber", questionNumber);
            feedback.put("userAnswer", userAnswer.trim());
            feedback.put("isCorrect", isCorrect);

            if (isCorrect) {
                feedback.put("feedback", "Correct! Well done!");
                feedback.put("feedbackType", "SUCCESS");
            } else {
                // RUBRIC REQUIREMENT: Show correct answer when wrong
                feedback.put("feedback", "Incorrect. The correct answer is: " + question.getCorrectAnswer());
                feedback.put("feedbackType", "ERROR");
                feedback.put("correctAnswer", question.getCorrectAnswer());
            }

            feedback.put("hasNext", questionNumber < Math.min(questions.size(), 10));
            feedback.put("totalQuestions", Math.min(questions.size(), 10));

            return ResponseEntity.ok(feedback);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to process answer: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}