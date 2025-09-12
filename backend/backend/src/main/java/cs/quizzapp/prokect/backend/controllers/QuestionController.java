package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.models.Question;
import cs.quizzapp.prokect.backend.models.Quiz;
import cs.quizzapp.prokect.backend.payload.QuizRequest;
import cs.quizzapp.prokect.backend.services.QuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    @Autowired
    private QuestionService questionService;
    // Get all questions
    @GetMapping
    public ResponseEntity<List<Question>> getAllQuestions() {
        List<Question> questions = questionService.getAllQuestions();
        return ResponseEntity.ok(questions);
    }

    // Get a question by ID
    @GetMapping("/{id}")
    public ResponseEntity<Question> getQuestionById(@PathVariable Long id) {
        Optional<Question> question = questionService.getQuestionById(id);
        return question.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    // Get questions for a specific quiz by Quiz ID
    @GetMapping("/quiz/{quizId}")
    public ResponseEntity<List<Question>> getQuestionsByQuizId(@PathVariable Long quizId) {
        List<Question> questions = questionService.getQuestionsByQuizId(quizId);
        return ResponseEntity.ok(questions.stream().limit(10).toList()); // Limit to 10 questions
    }

    // Fetch and save questions for a specific quiz
    @PostMapping("/fetch")
    public ResponseEntity<?> fetchAndSaveQuestions(@RequestBody QuizRequest quizRequest, @RequestBody Quiz quiz) {
        try {
            // Use the instance of questionService to call the fetchAndSaveQuestions method
            List<Question> questions = questionService.fetchAndSaveQuestions(quizRequest, quiz);
            return ResponseEntity.status(HttpStatus.CREATED).body(questions);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An error occurred while fetching questions: " + e.getMessage());
        }
    }

    // Add a new question
    @PostMapping
    public ResponseEntity<Question> addQuestion(@RequestBody Question question) {
        Question savedQuestion = questionService.addQuestion(question);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedQuestion);
    }

    // Update an existing question
    @PutMapping("/{id}")
    public ResponseEntity<Question> updateQuestion(@PathVariable Long id, @RequestBody Question updatedQuestion) {
        Optional<Question> question = questionService.updateQuestion(id, updatedQuestion);
        return question.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    // Delete a question
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuestion(@PathVariable Long id) {
        boolean isDeleted = questionService.deleteQuestion(id);
        return isDeleted ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
