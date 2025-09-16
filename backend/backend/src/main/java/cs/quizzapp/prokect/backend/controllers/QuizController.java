package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.services.OpenTDBService;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final List<SimpleQuiz> quizzes = new ArrayList<>();
    private Long nextId = 1L;

    @PostConstruct
    public void initializeQuizzes() {
        System.out.println("Starting OpenTDB quiz initialization...");

        // Test OpenTDB connection first
        try {
            List<OpenTDBService.Category> categories = openTDBService.fetchCategories();
            if (categories == null || categories.isEmpty()) {
                System.err.println("❌ OpenTDB connection failed - no categories available");
                System.err.println("❌ No quizzes will be created until OpenTDB is accessible");
                return;
            }
            System.out.println("✅ OpenTDB connection successful - " + categories.size() + " categories available");
        } catch (Exception e) {
            System.err.println("❌ OpenTDB connection error: " + e.getMessage());
            System.err.println("❌ No quizzes will be created until OpenTDB is accessible");
            return;
        }

        // Create quizzes asynchronously to avoid blocking startup
        CompletableFuture.runAsync(() -> {
            createOpenTDBQuizzes();
        });
    }

    private void createOpenTDBQuizzes() {
        System.out.println("Creating quizzes from OpenTDB (this may take a few moments due to rate limiting)...");

        // Popular categories to create quizzes for
        int[] popularCategoryIds = {9, 17, 21, 22, 23, 11, 12, 18, 19, 27};

        try {
            List<OpenTDBService.Category> categories = openTDBService.fetchCategories();

            for (int categoryId : popularCategoryIds) {
                try {
                    // Respect OpenTDB rate limit (1 request per 5 seconds)
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
                            System.out.println("✅ Created quiz: " + quiz.getTitle() + " with " +
                                    quiz.getQuestions().size() + " OpenTDB questions");
                        } else {
                            System.err.println("❌ Failed to create quiz for category: " + categoryOpt.get().getName());
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("❌ Quiz creation interrupted");
                    break;
                } catch (Exception e) {
                    System.err.println("❌ Error creating quiz for category ID " + categoryId + ": " + e.getMessage());
                }
            }

            System.out.println("✅ Quiz initialization complete. Created " + quizzes.size() + " quizzes from OpenTDB");

        } catch (Exception e) {
            System.err.println("❌ Failed to create OpenTDB quizzes: " + e.getMessage());
        }
    }

    private SimpleQuiz createQuizForCategory(OpenTDBService.Category category) {
        System.out.println("Fetching questions for category: " + category.getName() + " (ID: " + category.getId() + ")");

        // Try different difficulties to get questions
        String[] difficulties = {"easy", "medium", "hard", ""};
        List<OpenTDBService.Question> questions = null;

        for (String difficulty : difficulties) {
            try {
                questions = openTDBService.fetchQuestions(category.getId(), difficulty, "multiple", 10);
                if (questions != null && !questions.isEmpty()) {
                    System.out.println("✅ Found " + questions.size() + " questions with difficulty: " +
                            (difficulty.isEmpty() ? "any" : difficulty));
                    break;
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch " + difficulty + " questions: " + e.getMessage());
            }
        }

        if (questions == null || questions.isEmpty()) {
            System.err.println("❌ No questions available for category: " + category.getName());
            return null;
        }

        // Create quiz
        SimpleQuiz quiz = new SimpleQuiz();
        quiz.setId(nextId++);
        quiz.setTitle(category.getName() + " Quiz");
        quiz.setDescription("Test your knowledge in " + category.getName() + " with questions from OpenTDB");
        quiz.setCategory(category.getName());
        quiz.setDifficulty("mixed");
        quiz.setCreatedAt(LocalDateTime.now());

        // Convert OpenTDB questions to SimpleQuestion format
        List<SimpleQuestion> simpleQuestions = new ArrayList<>();
        for (OpenTDBService.Question q : questions) {
            try {
                SimpleQuestion sq = convertOpenTDBQuestion(q);
                if (sq != null) {
                    simpleQuestions.add(sq);
                }
            } catch (Exception e) {
                System.err.println("Error converting question: " + e.getMessage());
            }
        }

        quiz.setQuestions(simpleQuestions);
        System.out.println("✅ Successfully created quiz with " + simpleQuestions.size() + " valid questions");

        return quiz;
    }

    private SimpleQuestion convertOpenTDBQuestion(OpenTDBService.Question openTDBQuestion) {
        try {
            String question = openTDBQuestion.getQuestion();
            String correctAnswer = openTDBQuestion.getCorrectAnswer();
            List<String> incorrectAnswers = openTDBQuestion.getIncorrectAnswers();

            if (question == null || correctAnswer == null || incorrectAnswers == null) {
                return null;
            }

            // Create all options and shuffle them
            List<String> allOptions = new ArrayList<>();
            allOptions.add(correctAnswer);
            allOptions.addAll(incorrectAnswers);
            Collections.shuffle(allOptions);

            // Ensure we have exactly 4 options
            while (allOptions.size() < 4) {
                allOptions.add("No answer");
            }

            // Fill the 4 option slots
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

    // REST API Endpoints

    @GetMapping
    public List<SimpleQuiz> getAllQuizzes() {
        synchronized (quizzes) {
            return new ArrayList<>(quizzes);
        }
    }

    @GetMapping("/all")
    public List<SimpleQuiz> getAllQuizzesAlternative() {
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

    @PostMapping
    public SimpleQuiz createQuiz(@RequestBody SimpleQuiz quiz) {
        quiz.setId(nextId++);
        quiz.setCreatedAt(LocalDateTime.now());
        synchronized (quizzes) {
            quizzes.add(quiz);
        }
        return quiz;
    }

    @PostMapping("/refresh-all")
    public ResponseEntity<Map<String, Object>> refreshAllQuizzes() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Clear existing quizzes
            synchronized (quizzes) {
                quizzes.clear();
                nextId = 1L;
            }

            System.out.println("🔄 Refreshing all quizzes from OpenTDB...");

            // Recreate quizzes from OpenTDB
            CompletableFuture.runAsync(() -> {
                createOpenTDBQuizzes();
            });

            response.put("success", true);
            response.put("message", "Quiz refresh started. New quizzes will be available shortly.");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("error", "Failed to refresh quizzes: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/create-from-opentdb")
    public ResponseEntity<Map<String, Object>> createQuizFromOpenTDB(@RequestBody Map<String, Object> request) {
        try {
            String title = (String) request.get("title");
            String description = (String) request.get("description");
            Integer categoryId = (Integer) request.get("categoryId");
            String difficulty = (String) request.get("difficulty");
            String type = (String) request.get("type");
            Integer questionCount = (Integer) request.get("questionCount");

            if (title == null || title.trim().isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "Title is required");
                return ResponseEntity.badRequest().body(errorResponse);
            }
            if (questionCount == null || questionCount < 1 || questionCount > 50) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "Question count must be between 1 and 50");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            List<OpenTDBService.Question> openTDBQuestions = openTDBService.fetchQuestions(
                    categoryId, difficulty, type, questionCount);

            if (openTDBQuestions.isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "No questions available for the specified criteria. Try different parameters.");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            List<SimpleQuestion> questions = new ArrayList<>();
            for (OpenTDBService.Question openTDBQuestion : openTDBQuestions) {
                List<String> allAnswers = new ArrayList<>();
                allAnswers.add(openTDBQuestion.getCorrectAnswer());
                if (openTDBQuestion.getIncorrectAnswers() != null) {
                    allAnswers.addAll(openTDBQuestion.getIncorrectAnswers());
                }
                Collections.shuffle(allAnswers);

                while (allAnswers.size() < 4) {
                    allAnswers.add("No answer");
                }

                SimpleQuestion question = new SimpleQuestion(
                        openTDBQuestion.getQuestion(),
                        openTDBQuestion.getCorrectAnswer(),
                        allAnswers
                );
                questions.add(question);
            }

            SimpleQuiz newQuiz = new SimpleQuiz(nextId++, title,
                    description != null ? description : "Quiz created from OpenTDB",
                    openTDBQuestions.get(0).getCategory());

            newQuiz.setDifficulty(difficulty != null ? difficulty : "mixed");
            newQuiz.setQuestions(questions);

            synchronized (quizzes) {
                quizzes.add(newQuiz);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("quiz", newQuiz);
            response.put("questionsCount", questions.size());
            response.put("message", "Quiz created successfully with " + questions.size() + " real questions from OpenTDB");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to create quiz: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuiz(@PathVariable Long id) {
        synchronized (quizzes) {
            boolean removed = quizzes.removeIf(quiz -> quiz.getId().equals(id));
            return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<SimpleQuiz> updateQuiz(@PathVariable Long id, @RequestBody SimpleQuiz updatedQuiz) {
        synchronized (quizzes) {
            for (int i = 0; i < quizzes.size(); i++) {
                if (quizzes.get(i).getId().equals(id)) {
                    updatedQuiz.setId(id);
                    quizzes.set(i, updatedQuiz);
                    return ResponseEntity.ok(updatedQuiz);
                }
            }
            return ResponseEntity.notFound().build();
        }
    }

    // OpenTDB endpoints
    @GetMapping("/categories")
    public ResponseEntity<List<OpenTDBService.Category>> getCategories() {
        try {
            List<OpenTDBService.Category> categories = openTDBService.fetchCategories();
            return ResponseEntity.ok(categories);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ArrayList<>());
        }
    }

    @GetMapping("/difficulties")
    public ResponseEntity<List<String>> getDifficulties() {
        return ResponseEntity.ok(openTDBService.getAvailableDifficulties());
    }

    @GetMapping("/types")
    public ResponseEntity<List<String>> getQuestionTypes() {
        return ResponseEntity.ok(openTDBService.getAvailableTypes());
    }

    @PostMapping("/validate-parameters")
    public ResponseEntity<Map<String, Object>> validateQuizParameters(@RequestBody Map<String, Object> request) {
        try {
            Integer categoryId = (Integer) request.get("categoryId");
            String difficulty = (String) request.get("difficulty");
            String type = (String) request.get("type");
            Integer questionCount = (Integer) request.get("questionCount");

            List<OpenTDBService.Question> testQuestions = openTDBService.fetchQuestions(
                    categoryId, difficulty, type, Math.min(questionCount != null ? questionCount : 5, 5));

            Map<String, Object> response = new HashMap<>();
            response.put("valid", !testQuestions.isEmpty());
            response.put("availableQuestions", testQuestions.size());

            if (testQuestions.isEmpty()) {
                response.put("message", "No questions available for these parameters");
            } else {
                response.put("message", "Parameters are valid");
                response.put("sampleQuestion", testQuestions.get(0).getQuestion());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("valid", false);
            response.put("message", "Error validating parameters: " + e.getMessage());
            return ResponseEntity.ok(response);
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
            testResult.put("categories", categories.size() > 5 ? categories.subList(0, 5) : categories);

            if (!sampleQuestions.isEmpty()) {
                testResult.put("sampleQuestion", sampleQuestions.get(0).getQuestion());
            }

            return ResponseEntity.ok(testResult);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "OpenTDB connection failed");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @GetMapping("/player/{userId}/quiz-history")
    public ResponseEntity<Map<String, Object>> getPlayerQuizHistory(@PathVariable Long userId) {
        try {
            Map<String, Object> history = new HashMap<>();
            history.put("userId", userId);

            synchronized (quizzes) {
                history.put("totalQuizzes", quizzes.size());

                List<Map<String, Object>> recentAttempts = new ArrayList<>();
                for (int i = 0; i < Math.min(3, quizzes.size()); i++) {
                    Map<String, Object> attempt = new HashMap<>();
                    attempt.put("quizId", quizzes.get(i).getId());
                    attempt.put("quizTitle", quizzes.get(i).getTitle());
                    attempt.put("score", 70 + (i * 10));
                    attempt.put("completedAt", LocalDateTime.now().minusDays(i + 1).toString());
                    recentAttempts.add(attempt);
                }
                history.put("recentAttempts", recentAttempts);
            }

            history.put("averageScore", 75.5);

            List<String> favoriteCategories = new ArrayList<>();
            favoriteCategories.add("General Knowledge");
            favoriteCategories.add("Science & Nature");
            history.put("favoriteCategories", favoriteCategories);

            return ResponseEntity.ok(history);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch quiz history: " + e.getMessage());
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

        public SimpleQuiz(Long id, String title, String description, String category) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.category = category;
            this.difficulty = "easy";
            this.createdAt = LocalDateTime.now();
        }

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

        public SimpleQuestion(String questionText, String correctAnswer, List<String> options) {
            this.questionText = questionText;
            this.correctAnswer = correctAnswer;
            if (options.size() >= 1) this.option1 = options.get(0);
            if (options.size() >= 2) this.option2 = options.get(1);
            if (options.size() >= 3) this.option3 = options.get(2);
            if (options.size() >= 4) this.option4 = options.get(3);
        }

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