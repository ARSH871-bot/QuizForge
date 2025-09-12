package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.dto.QuizSummaryDTO;
import cs.quizzapp.prokect.backend.models.Category;
import cs.quizzapp.prokect.backend.models.Quiz;
import cs.quizzapp.prokect.backend.models.User;
import cs.quizzapp.prokect.backend.payload.QuizRequest;
import cs.quizzapp.prokect.backend.services.CategoryService;
import cs.quizzapp.prokect.backend.services.QuizService;
import cs.quizzapp.prokect.backend.services.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    @Autowired
    private QuizService quizService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    public UserController(UserService userService, CategoryService categoryService) {
        this.userService = userService;
        this.categoryService = categoryService;
    }

    // ------------------ User Management Endpoints ------------------
    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        return userService.findUserById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Long id, @RequestBody User user) {
        return userService.updateUser(id, user)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        if (userService.deleteUser(id)) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // ------------------ Quiz Management for Admins ------------------
    @PostMapping("/admin/quizzes")
    public ResponseEntity<String> createQuizWithQuestions(@RequestBody QuizRequest quizRequest) {
        try {
            Quiz quiz = quizService.createQuizWithQuestions(quizRequest);
            //QuizService.fetchAndSaveQuestions(quizRequest, quiz);
            return ResponseEntity.ok("Quiz created successfully!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error creating quiz: " + e.getMessage());
        }
    }

    @GetMapping("/admin/quizzes")
    public ResponseEntity<List<Quiz>> getAllQuizzes() {
        return ResponseEntity.ok(quizService.getAllQuizzes());
    }

    @GetMapping("/admin/quizzes/{id}")
    public ResponseEntity<Quiz> getQuizById(@PathVariable Long id) {
        return quizService.getQuizById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/admin/quizzes/{id}")
    public ResponseEntity<QuizSummaryDTO> updateQuiz(@PathVariable Long id, @RequestBody QuizRequest updatedQuizRequest) {
        try {
            Quiz updatedQuiz = quizService.updateQuiz(id, updatedQuizRequest);

            if (updatedQuiz != null) {
                // Create a DTO to avoid nested questions
                QuizSummaryDTO quizSummaryDTO = new QuizSummaryDTO();
                quizSummaryDTO.setId(updatedQuiz.getId());
                quizSummaryDTO.setName(updatedQuiz.getName());
                quizSummaryDTO.setCategory(updatedQuiz.getCategory());
                quizSummaryDTO.setDifficulty(updatedQuiz.getDifficulty());
                quizSummaryDTO.setStartDate(updatedQuiz.getStartDate());
                quizSummaryDTO.setEndDate(updatedQuiz.getEndDate());
                quizSummaryDTO.setLikesCount(updatedQuiz.getLikesCount());
                quizSummaryDTO.setRating(updatedQuiz.getRating());
                quizSummaryDTO.setRatingCount(updatedQuiz.getRatingCount());

                // Return updated quiz details without nested questions
                return ResponseEntity.ok(quizSummaryDTO);
            } else {
                return ResponseEntity.status(404).build(); // Quiz not found
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null); // Return internal server error in case of an exception
        }
    }


    @DeleteMapping("/admin/quizzes/{id}")
    public ResponseEntity<String> deleteQuiz(@PathVariable Long id, @RequestParam(required = false) Boolean confirmDelete) {
        if (confirmDelete == null || !confirmDelete) {
            // Prompt the client for confirmation
            return ResponseEntity.badRequest().body("Are you sure you want to delete this quiz? Set confirmDelete=true to proceed.");
        }

        boolean isDeleted = quizService.deleteQuiz(id);
        if (isDeleted) {
            return ResponseEntity.ok("Quiz deleted successfully.");
        } else {
            return ResponseEntity.status(404).body("Quiz not found.");
        }
    }
    @GetMapping("/admin/quizzes/likes")
    public ResponseEntity<List<Map<String, Object>>> getLikesForAllQuizzes() {
        List<Quiz> quizzes = quizService.getAllQuizzes();

        // Create a list of maps to store quiz name and likes count
        List<Map<String, Object>> quizLikesList = quizzes.stream().map(quiz -> {
            Map<String, Object> quizInfo = new HashMap<>();
            quizInfo.put("id", quiz.getId());
            quizInfo.put("name", quiz.getName());
            quizInfo.put("likesCount", quiz.getLikesCount());
            return quizInfo;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(quizLikesList);
    }

    // extra features v.t
    @GetMapping("/admin/quizzes/statistics")
    public ResponseEntity<List<Map<String, Object>>> getQuizStatistics() {
        List<Quiz> quizzes = quizService.getAllQuizzes();
        List<Map<String, Object>> quizStatsList = quizzes.stream().map(quiz -> {
            Map<String, Object> quizStats = new HashMap<>();
            quizStats.put("id", quiz.getId());
            quizStats.put("name", quiz.getName());
            quizStats.put("likesCount", quiz.getLikesCount());
            quizStats.put("rating", quiz.getRating());
            //quizStats.put("numberOfParticipants", quiz.getParticipants().size());
            return quizStats;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(quizStatsList);
    }

    //2
    @PostMapping("/admin/categories")
    public ResponseEntity<String> createCategory(@RequestBody String categoryName) {
        try {
            categoryService.addCategory(categoryName);
            return ResponseEntity.ok("Category created successfully.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Error creating category: " + e.getMessage());
        }
    }
    @GetMapping("/admin/categories")
    public ResponseEntity<List<Category>> getAllCategories() {
        List<Category> categories = categoryService.getAllCategories();
        return ResponseEntity.ok(categories);
    }

    public ResponseEntity<String> deleteCategory(@RequestBody String categoryName) {
        try {
            categoryService.deleteCategory(categoryName);
            return ResponseEntity.ok("Category deleted successfully.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Error deleting category: " + e.getMessage());
        }
    }
}
