package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.dto.QuestionDTO;
import cs.quizzapp.prokect.backend.dto.QuizDTO;
import cs.quizzapp.prokect.backend.dto.QuizSummaryDTO;
import cs.quizzapp.prokect.backend.models.Question;
import cs.quizzapp.prokect.backend.models.Quiz;
import cs.quizzapp.prokect.backend.payload.QuizRequest;
import cs.quizzapp.prokect.backend.services.QuestionService;
import cs.quizzapp.prokect.backend.services.QuizService;
import cs.quizzapp.prokect.backend.utils.QuizCategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/quizzes")
public class QuizController {

    @Autowired
    private QuizService quizService;

    @Autowired
    private QuestionService questionService;



    /**
     * Create a new quiz and fetch questions dynamically from OpenTDB.
     */
    @PostMapping("/create")
    public ResponseEntity<String> createQuizWithQuestions(@RequestBody QuizRequest quizRequest) {
        try {
            // Validate the category
            Integer categoryId = QuizCategoryMapper.getCategoryId(quizRequest.getCategory());
            if (categoryId == null) {
                return ResponseEntity.badRequest().body("Invalid category. Available categories: " + QuizCategoryMapper.getAllCategories());
            }

            // Set the numeric category ID
            quizRequest.setCategoryId(categoryId);

            // Check if a quiz with the same name already exists
            Quiz quiz = quizService.getQuizByName(quizRequest.getName());
            if (quiz == null) {
                // Create a new quiz if it doesn't exist
                quiz = quizService.createQuizWithQuestions(quizRequest);

                return ResponseEntity.ok("Quiz created successfully!");
            }
            // Fetch and save questions using the injected QuestionService instance
            List<Question> questions = questionService.fetchAndSaveQuestions(quizRequest, quiz);

            if (questions.isEmpty()) {
                return ResponseEntity.badRequest().body("Failed to fetch questions. Check your inputs.");
            }

            return ResponseEntity.ok("Quiz created successfully with " + questions.size() + " questions.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error creating quiz: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<Quiz>> getAllQuizzes() {
        return ResponseEntity.ok(quizService.getAllQuizzes());
    }
    @GetMapping("/all")
    public ResponseEntity<List<QuizSummaryDTO>> getAllQuizzesDTO() {
        // Fetch all quizzes using the service
        List<Quiz> quizzes = quizService.getAllQuizzes();

        // Map Quiz objects to QuizSummaryDTOs to avoid unnecessary nesting
        List<QuizSummaryDTO> quizSummaryDTOs = quizzes.stream().map(quiz -> {
            QuizSummaryDTO quizSummaryDTO = new QuizSummaryDTO();

            // Set Quiz fields
            quizSummaryDTO.setId(quiz.getId());
            quizSummaryDTO.setName(quiz.getName());
            quizSummaryDTO.setCategory(quiz.getCategory());
            quizSummaryDTO.setDifficulty(quiz.getDifficulty());
            quizSummaryDTO.setStartDate(quiz.getStartDate());
            quizSummaryDTO.setEndDate(quiz.getEndDate());
            quizSummaryDTO.setLikesCount(quiz.getLikesCount());
            quizSummaryDTO.setRating(quiz.getRating());


            // Set the number of questions
            quizSummaryDTO.setNumberOfQuestions(quiz.getQuestions().size());

            return quizSummaryDTO;
        }).collect(Collectors.toList());

        // Return response with all quizzes as DTOs
        return ResponseEntity.ok(quizSummaryDTOs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuizDTO> getQuizById(@PathVariable Long id) {
        Optional<Quiz> quizOptional = quizService.getQuizById(id);
        //return ResponseEntity.of(quizService.getQuizById(id));
        if (quizOptional.isPresent()) {
            Quiz quiz = quizOptional.get();

            // Fetch only 10 questions for this quiz
            List<Question> limitedQuestions = questionService.getQuestionsByQuizId(quiz.getId())
                    .stream()
                    .limit(10)
                    .collect(Collectors.toList());
            // Map Questions to QuestionDTOs
            List<QuestionDTO> questionDTOs = limitedQuestions.stream()
                    .map(question -> {
                        QuestionDTO questionDTO = new QuestionDTO();
                        questionDTO.setId(question.getId());
                        questionDTO.setQuestionText(question.getQuestionText());
                        questionDTO.setOptions(question.getOptions());
                        questionDTO.setCorrectAnswer(question.getCorrectAnswer());

                        return questionDTO;
                    })
                    .collect(Collectors.toList());


            // Initialize quizDTO and set its fields
            QuizDTO quizDTO = new QuizDTO();
            quizDTO.setId(quiz.getId());
            quizDTO.setName(quiz.getName());
            quizDTO.setCategory(quiz.getCategory());
            quizDTO.setDifficulty(quiz.getDifficulty());
            quizDTO.setStartDate(quiz.getStartDate());
            quizDTO.setEndDate(quiz.getEndDate());
            quizDTO.setQuestions(questionDTOs);

            quiz.setQuestions(limitedQuestions); // Update the quiz object with limited questions
            return ResponseEntity.ok(quizDTO);
        } else {
            return ResponseEntity.status(404).build(); // Quiz not found
        }
    }

    @GetMapping("/categories")
    public ResponseEntity<?> getAvailableCategories() {
        return ResponseEntity.ok(QuizCategoryMapper.getAllCategories());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteQuiz(@PathVariable Long id) {
        boolean isDeleted = quizService.deleteQuiz(id);
        return isDeleted ? ResponseEntity.ok("Quiz deleted successfully.") : ResponseEntity.notFound().build();
    }

    // Get ongoing quizzes
    @GetMapping("/ongoing")
    public ResponseEntity<List<Quiz>> getOngoingQuizzes() {
        return ResponseEntity.ok(quizService.getOngoingQuizzes());
    }

    // Get upcoming quizzes
    @GetMapping("/upcoming")
    public ResponseEntity<List<Quiz>> getUpcomingQuizzes() {
        return ResponseEntity.ok(quizService.getUpcomingQuizzes());
    }

    // Get past quizzes
    @GetMapping("/past")
    public ResponseEntity<List<Quiz>> getPastQuizzes() {
        return ResponseEntity.ok(quizService.getPastQuizzes());
    }

    // Get participated quizzes
    @GetMapping("/participated")
    public ResponseEntity<List<Quiz>> getParticipatedQuizzes(@RequestParam Long userId) {
        List<Quiz> quizzes = quizService.getParticipatedQuizzes(userId);
        return ResponseEntity.ok(quizzes);
    }

    // Play quiz
    @PostMapping("/{quizId}/play")
    public ResponseEntity<Object> playQuiz(@PathVariable Long quizId, @RequestParam Long userId) {
        try {
            List<Question> questions = quizService.playQuiz(quizId, userId);
            return ResponseEntity.ok(questions);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An unexpected error occurred.");
        }
    }


    // After submit answers it will display the feedback according to correct or incorrect answers.
    @PostMapping("/{quizId}/user/{userId}/submit")
    public ResponseEntity<Map<String, Object>> submitAnswers(
            @PathVariable Long quizId,
            @PathVariable Long userId,
            @RequestBody Map<Long, String> answers) {

        Map<String, Object> response = quizService.submitAnswers(quizId, userId, answers);
        return ResponseEntity.ok(response);
    }

    // Post a like
    @PostMapping("/{quizId}/like")
    public ResponseEntity<String> likeQuiz(@PathVariable Long quizId) {
        try {
            quizService.likeQuiz(quizId);
            return ResponseEntity.ok("Liked successfully!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error liking quiz");
        }
    }

    // Post an unlike
    @PostMapping("/{quizId}/unlike")
    public ResponseEntity<String> unlikeQuiz(@PathVariable Long quizId) {
        try {
            quizService.unlikeQuiz(quizId);
            return ResponseEntity.ok("Unliked successfully!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error unliking quiz");
        }
    }

    // Additional features
    // Play the quiz again
    @PostMapping("/{quizId}/user/{userId}/replay")
    public ResponseEntity<Map<String, Object>> replayQuiz(
            @PathVariable Long quizId,
            @PathVariable Long userId,
            @RequestBody Map<Long, String> playerAnswers
    ) {
        // Call the service method to handle replay logic
        Map<String, Object> response = quizService.replayQuiz(quizId, userId, playerAnswers);

        // Return the response with score and feedback
        return ResponseEntity.ok(response);
    }

    // Get the rating
    @GetMapping("/{quizId}/rating")
    public ResponseEntity<Map<String, Object>> getQuizRating(@PathVariable Long quizId) {
        Quiz quiz = quizService.getQuizById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

        Map<String, Object> response = new HashMap<>();
        response.put("rating", quiz.getRating());
        response.put("ratingCount", quiz.getRatingCount());
        return ResponseEntity.ok(response);
    }

    // Add the rating
    @PostMapping("/{quizId}/rate")
    public ResponseEntity<String> rateQuiz(
            @PathVariable Long quizId,
            @RequestParam int rating) {
        if (rating < 1 || rating > 5) {
            return ResponseEntity.badRequest().body("Rating must be between 1 and 5.");
        }

        try {
            quizService.addRating(quizId, rating);
            return ResponseEntity.ok("Rating submitted successfully!");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

}
