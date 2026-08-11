package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.services.OpenTDBService;
import cs.quizzapp.prokect.backend.services.QuizService;
import cs.quizzapp.prokect.backend.services.UserService;
import cs.quizzapp.prokect.backend.models.Quiz;
import cs.quizzapp.prokect.backend.models.Question;
import cs.quizzapp.prokect.backend.payload.QuizRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/quizzes")
@CrossOrigin(origins = "http://localhost:3000")
public class QuizController {

    @Autowired
    private OpenTDBService openTDBService;

    @Autowired
    private QuizService quizService;

    @Autowired
    private UserService userService;

    private final List<SimpleQuiz> quizzes = new ArrayList<>();
    private Long nextId = 1L;

    @org.springframework.beans.factory.annotation.Value("${quizforge.opentdb.bootstrap-enabled:true}")
    private boolean openTdbBootstrapEnabled;

    @PostConstruct
    public void initializeQuizzes() {
        if (!openTdbBootstrapEnabled) {
            System.out.println("OpenTDB bootstrap disabled by configuration; skipping.");
            return;
        }
        System.out.println("Starting OpenTDB quiz initialization...");

        try {
            List<OpenTDBService.Category> categories = openTDBService.fetchCategories();
            if (categories == null || categories.isEmpty()) {
                System.err.println("OpenTDB connection failed - no categories available");
                return;
            }
            System.out.println("OpenTDB connection successful - " + categories.size() + " categories available");
        } catch (Exception e) {
            System.err.println("OpenTDB connection error: " + e.getMessage());
            return;
        }

        CompletableFuture.runAsync(() -> {
            createOpenTDBQuizzes();
        });
    }

    private void createOpenTDBQuizzes() {
        System.out.println("Creating quizzes from OpenTDB...");

        int[] popularCategoryIds = {9, 17, 21, 22, 23};

        try {
            List<OpenTDBService.Category> categories = openTDBService.fetchCategories();

            for (int categoryId : popularCategoryIds) {
                try {
                    Thread.sleep(6000);

                    Optional<OpenTDBService.Category> categoryOpt = categories.stream()
                            .filter(cat -> cat.getId() == categoryId)
                            .findFirst();

                    if (categoryOpt.isPresent()) {
                        SimpleQuiz quiz = createQuizForCategory(categoryOpt.get());
                        if (quiz != null) {
                            synchronized (quizzes) {
                                quizzes.add(quiz);
                            }
                            System.out.println("Created quiz: " + quiz.getTitle());
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Error creating quiz for category ID " + categoryId + ": " + e.getMessage());
                }
            }

            System.out.println("Quiz initialization complete. Created " + quizzes.size() + " quizzes");

        } catch (Exception e) {
            System.err.println("Failed to create OpenTDB quizzes: " + e.getMessage());
        }
    }

    private SimpleQuiz createQuizForCategory(OpenTDBService.Category category) {
        try {
            List<OpenTDBService.Question> questions = openTDBService.fetchQuestions(category.getId(), "easy", "multiple", 10);

            if (questions == null || questions.isEmpty()) {
                return null;
            }

            SimpleQuiz quiz = new SimpleQuiz();
            quiz.setId(nextId++);
            quiz.setTitle(category.getName() + " Quiz");
            quiz.setDescription("Test your knowledge in " + category.getName());
            quiz.setCategory(category.getName());
            quiz.setDifficulty("easy");
            quiz.setCreatedAt(LocalDateTime.now());

            List<SimpleQuestion> simpleQuestions = new ArrayList<>();
            for (OpenTDBService.Question q : questions) {
                SimpleQuestion sq = convertOpenTDBQuestion(q);
                if (sq != null) {
                    simpleQuestions.add(sq);
                }
            }

            quiz.setQuestions(simpleQuestions);
            return quiz;

        } catch (Exception e) {
            System.err.println("Error creating quiz for category: " + category.getName());
            return null;
        }
    }

    private SimpleQuestion convertOpenTDBQuestion(OpenTDBService.Question openTDBQuestion) {
        try {
            String question = openTDBQuestion.getQuestion();
            String correctAnswer = openTDBQuestion.getCorrectAnswer();
            List<String> incorrectAnswers = openTDBQuestion.getIncorrectAnswers();

            if (question == null || correctAnswer == null || incorrectAnswers == null) {
                return null;
            }

            List<String> allOptions = new ArrayList<>();
            allOptions.add(correctAnswer);
            allOptions.addAll(incorrectAnswers);
            Collections.shuffle(allOptions);

            while (allOptions.size() < 4) {
                allOptions.add("No answer");
            }

            SimpleQuestion sq = new SimpleQuestion();
            sq.setQuestionText(question);
            sq.setCorrectAnswer(correctAnswer);
            sq.setOption1(allOptions.get(0));
            sq.setOption2(allOptions.get(1));
            sq.setOption3(allOptions.get(2));
            sq.setOption4(allOptions.get(3));

            return sq;

        } catch (Exception e) {
            System.err.println("Error converting OpenTDB question: " + e.getMessage());
            return null;
        }
    }

    // === ADMIN ENDPOINTS ===

    @PostMapping("/tournaments")
    public ResponseEntity<?> createQuizTournament(@RequestBody QuizRequest quizRequest) {
        try {
            if (quizRequest.getName() == null || quizRequest.getName().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Quiz name is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            Quiz createdQuiz = quizService.createQuizWithQuestions(quizRequest);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Quiz tournament created successfully");
            response.put("quiz", createdQuiz);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to create quiz tournament: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/tournaments")
    public ResponseEntity<?> getAllQuizTournaments() {
        try {
            List<Quiz> tournaments = quizService.getAllQuizzes();
            return ResponseEntity.ok(tournaments);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve quiz tournaments: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PutMapping("/tournaments/{id}")
    public ResponseEntity<?> updateQuizTournament(@PathVariable Long id, @RequestBody QuizRequest quizRequest) {
        try {
            Quiz updatedQuiz = quizService.updateQuiz(id, quizRequest);

            if (updatedQuiz != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", "Quiz tournament updated successfully");
                response.put("quiz", updatedQuiz);
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Quiz tournament not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to update quiz tournament: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @DeleteMapping("/tournaments/{id}")
    public ResponseEntity<?> deleteQuizTournament(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean confirm) {
        try {
            if (!confirm) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Are you sure you want to delete this quiz tournament? Add ?confirm=true to proceed.");
                return ResponseEntity.ok(response);
            }

            boolean deleted = quizService.deleteQuiz(id);

            if (deleted) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Quiz tournament deleted successfully");
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Quiz tournament not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to delete quiz tournament: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/tournaments/likes")
    public ResponseEntity<?> getQuizLikes() {
        try {
            List<Quiz> quizzes = quizService.getAllQuizzes();
            List<Map<String, Object>> likesData = new ArrayList<>();

            for (Quiz quiz : quizzes) {
                Map<String, Object> quizLikes = new HashMap<>();
                quizLikes.put("quizId", quiz.getId());
                quizLikes.put("quizName", quiz.getName());
                quizLikes.put("likesCount", quiz.getLikesCount());
                likesData.add(quizLikes);
            }

            return ResponseEntity.ok(likesData);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve quiz likes: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // === PLAYER ENDPOINTS ===

    @GetMapping("/player/{userId}/tournaments")
    public ResponseEntity<?> getPlayerTournaments(@PathVariable Long userId) {
        try {
            if (!userService.findUserById(userId).isPresent()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            Map<String, Object> tournaments = new HashMap<>();
            tournaments.put("ongoing", quizService.getOngoingQuizzes());
            tournaments.put("upcoming", quizService.getUpcomingQuizzes());
            tournaments.put("past", quizService.getPastQuizzes());
            tournaments.put("participated", quizService.getParticipatedQuizzes(userId));

            return ResponseEntity.ok(tournaments);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve player tournaments: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/tournaments/{quizId}/participate/{userId}")
    public ResponseEntity<?> participateInTournament(@PathVariable Long quizId, @PathVariable Long userId) {
        try {
            List<Question> questions = quizService.playQuiz(quizId, userId);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Quiz started successfully");
            response.put("questions", questions);
            response.put("totalQuestions", questions.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to start quiz participation: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/tournaments/{quizId}/submit/{userId}")
    public ResponseEntity<?> submitQuizAnswers(@PathVariable Long quizId, @PathVariable Long userId,
                                               @RequestBody Map<String, String> answers) {
        try {
            Map<Long, String> questionAnswers = new HashMap<>();
            for (Map.Entry<String, String> entry : answers.entrySet()) {
                try {
                    Long questionId = Long.parseLong(entry.getKey());
                    questionAnswers.put(questionId, entry.getValue());
                } catch (NumberFormatException e) {
                    Map<String, String> error = new HashMap<>();
                    error.put("error", "Invalid question ID format: " + entry.getKey());
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
                }
            }

            Map<String, Object> result = quizService.submitAnswers(quizId, userId, questionAnswers);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to submit quiz answers: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/tournaments/{quizId}/like/{userId}")
    public ResponseEntity<?> likeQuizTournament(@PathVariable Long quizId, @PathVariable Long userId) {
        try {
            boolean liked = quizService.likeQuiz(userId, quizId);

            Map<String, Object> response = new HashMap<>();
            response.put("message", liked ? "Quiz tournament liked successfully" : "Quiz tournament already liked");
            response.put("action", liked ? "liked" : "already_liked");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to like quiz tournament: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @DeleteMapping("/tournaments/{quizId}/like/{userId}")
    public ResponseEntity<?> unlikeQuizTournament(@PathVariable Long quizId, @PathVariable Long userId) {
        try {
            boolean unliked = quizService.unlikeQuiz(userId, quizId);

            Map<String, Object> response = new HashMap<>();
            response.put("message", unliked ? "Quiz tournament unliked successfully" : "Quiz tournament was not liked");
            response.put("action", unliked ? "unliked" : "not_liked");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to unlike quiz tournament: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // === EXISTING ENDPOINTS ===

    @GetMapping
    public List<SimpleQuiz> getAllQuizzes() {
        synchronized (quizzes) {
            return new ArrayList<>(quizzes);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<SimpleQuiz> getQuizById(@PathVariable Long id) {
        synchronized (quizzes) {
            Optional<SimpleQuiz> quiz = quizzes.stream()
                    .filter(q -> q.getId().equals(id))
                    .findFirst();
            return quiz.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
        }
    }

    @GetMapping("/categories")
    public ResponseEntity<List<OpenTDBService.Category>> getCategories() {
        try {
            List<OpenTDBService.Category> categories = openTDBService.fetchCategories();
            return ResponseEntity.ok(categories);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ArrayList<>());
        }
    }

    @GetMapping("/test-opentdb")
    public ResponseEntity<Map<String, Object>> testOpenTDB() {
        try {
            List<OpenTDBService.Category> categories = openTDBService.fetchCategories();
            List<OpenTDBService.Question> sampleQuestions = openTDBService.fetchQuestions(9, "easy", "multiple", 2);

            Map<String, Object> testResult = new HashMap<>();
            testResult.put("categoriesAvailable", categories.size());
            testResult.put("sampleQuestionsRetrieved", sampleQuestions.size());
            testResult.put("status", "OpenTDB connection working");

            return ResponseEntity.ok(testResult);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "OpenTDB connection failed");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    // Helper classes
    public static class SimpleQuiz {
        private Long id;
        private String title;
        private String description;
        private String category;
        private String difficulty;
        private LocalDateTime createdAt;
        private List<SimpleQuestion> questions = new ArrayList<>();

        public SimpleQuiz() {}

        // Getters and setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getDifficulty() { return difficulty; }
        public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public List<SimpleQuestion> getQuestions() { return questions; }
        public void setQuestions(List<SimpleQuestion> questions) { this.questions = questions; }
    }

    public static class SimpleQuestion {
        private Long id;
        private String questionText;
        private String option1;
        private String option2;
        private String option3;
        private String option4;
        private String correctAnswer;

        public SimpleQuestion() {}

        // Getters and setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getQuestionText() { return questionText; }
        public void setQuestionText(String questionText) { this.questionText = questionText; }
        public String getOption1() { return option1; }
        public void setOption1(String option1) { this.option1 = option1; }
        public String getOption2() { return option2; }
        public void setOption2(String option2) { this.option2 = option2; }
        public String getOption3() { return option3; }
        public void setOption3(String option3) { this.option3 = option3; }
        public String getOption4() { return option4; }
        public void setOption4(String option4) { this.option4 = option4; }
        public String getCorrectAnswer() { return correctAnswer; }
        public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }
    }
}