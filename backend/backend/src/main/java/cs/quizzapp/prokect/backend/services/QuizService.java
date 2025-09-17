package cs.quizzapp.prokect.backend.services;

import cs.quizzapp.prokect.backend.db.*;
import cs.quizzapp.prokect.backend.models.*;
import cs.quizzapp.prokect.backend.payload.QuizRequest;
import cs.quizzapp.prokect.backend.utils.QuizCategoryMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class QuizService {

    private final ParticipationRepository participationRepository;
    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final QuestionService questionService;
    private final ScoreRepository scoreRepository;
    private final QuestionRepository questionRepository;
    private final LikeRepository likeRepository;

    @Autowired
    private EmailService emailService;

    public QuizService(ParticipationRepository participationRepository,
                       QuizRepository quizRepository,
                       UserRepository userRepository,
                       QuestionService questionService,
                       ScoreRepository scoreRepository,
                       QuestionRepository questionRepository,
                       LikeRepository likeRepository) {
        this.participationRepository = participationRepository;
        this.quizRepository = quizRepository;
        this.userRepository = userRepository;
        this.questionService = questionService;
        this.scoreRepository = scoreRepository;
        this.questionRepository = questionRepository;
        this.likeRepository = likeRepository;
    }

    // === Like/Unlike Methods ===

    public boolean likeQuiz(Long userId, Long quizId) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            Quiz quiz = quizRepository.findById(quizId)
                    .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

            Optional<Like> existingLike = likeRepository.findByUserAndQuiz(user, quiz);
            if (existingLike.isPresent()) {
                return false;
            }

            Like like = new Like(user, quiz);
            likeRepository.save(like);

            quiz.setLikesCount(quiz.getLikesCount() + 1);
            quizRepository.save(quiz);

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to like quiz: " + e.getMessage(), e);
        }
    }

    public boolean unlikeQuiz(Long userId, Long quizId) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            Quiz quiz = quizRepository.findById(quizId)
                    .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

            Optional<Like> existingLike = likeRepository.findByUserAndQuiz(user, quiz);
            if (existingLike.isEmpty()) {
                return false;
            }

            likeRepository.delete(existingLike.get());

            if (quiz.getLikesCount() > 0) {
                quiz.setLikesCount(quiz.getLikesCount() - 1);
                quizRepository.save(quiz);
            }

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to unlike quiz: " + e.getMessage(), e);
        }
    }

    // === Leaderboard Methods ===

    public List<Map<String, Object>> getLeaderboard() {
        return getLeaderboard(10);
    }

    public List<Map<String, Object>> getLeaderboard(int limit) {
        try {
            List<Score> allScores = scoreRepository.findAll();

            Map<User, Double> userTotalScores = new HashMap<>();
            Map<User, Integer> userQuizCounts = new HashMap<>();

            for (Score score : allScores) {
                User user = score.getUser();
                userTotalScores.put(user, userTotalScores.getOrDefault(user, 0.0) + score.getScore());
                userQuizCounts.put(user, userQuizCounts.getOrDefault(user, 0) + 1);
            }

            List<Map<String, Object>> leaderboard = new ArrayList<>();
            for (Map.Entry<User, Double> entry : userTotalScores.entrySet()) {
                User user = entry.getKey();
                double totalScore = entry.getValue();
                int quizCount = userQuizCounts.get(user);
                double averageScore = totalScore / quizCount;

                Map<String, Object> entryMap = new HashMap<>();
                entryMap.put("userId", user.getId());
                entryMap.put("username", user.getUsername());
                entryMap.put("firstName", user.getFirstName());
                entryMap.put("lastName", user.getLastName());
                entryMap.put("totalScore", totalScore);
                entryMap.put("quizCount", quizCount);
                entryMap.put("averageScore", Math.round(averageScore * 100.0) / 100.0);
                entryMap.put("rank", 0);

                leaderboard.add(entryMap);
            }

            leaderboard.sort((a, b) -> Double.compare((Double) b.get("averageScore"), (Double) a.get("averageScore")));

            for (int i = 0; i < Math.min(limit, leaderboard.size()); i++) {
                leaderboard.get(i).put("rank", i + 1);
            }

            return leaderboard.stream().limit(limit).collect(Collectors.toList());

        } catch (Exception e) {
            throw new RuntimeException("Failed to get leaderboard: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> getQuizLeaderboard(Long quizId) {
        return getQuizLeaderboard(quizId, 10);
    }

    public List<Map<String, Object>> getQuizLeaderboard(Long quizId, int limit) {
        try {
            Quiz quiz = quizRepository.findById(quizId)
                    .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

            List<Score> scores = scoreRepository.findByQuizIdOrderByScoreDesc(quizId);

            List<Map<String, Object>> leaderboard = new ArrayList<>();
            int rank = 1;

            for (Score score : scores) {
                if (rank > limit) break;

                User user = score.getUser();
                Map<String, Object> entry = new HashMap<>();
                entry.put("rank", rank++);
                entry.put("userId", user.getId());
                entry.put("username", user.getUsername());
                entry.put("firstName", user.getFirstName());
                entry.put("lastName", user.getLastName());
                entry.put("score", score.getScore());
                entry.put("completedDate", score.getCompletedDate());

                leaderboard.add(entry);
            }

            return leaderboard;

        } catch (Exception e) {
            throw new RuntimeException("Failed to get quiz leaderboard: " + e.getMessage(), e);
        }
    }

    // === Existing Quiz Methods ===

    public Quiz createQuizWithQuestions(QuizRequest quizRequest) {
        validateQuizTiming(quizRequest.getStartDate(), quizRequest.getEndDate());

        Integer categoryId = QuizCategoryMapper.getCategoryId(quizRequest.getCategory());
        if (categoryId == null) {
            throw new IllegalArgumentException("Invalid category name provided: " + quizRequest.getCategory());
        }

        Quiz quiz = new Quiz();
        quiz.setName(quizRequest.getName());
        quiz.setCategory(quizRequest.getCategory());
        quiz.setDifficulty(quizRequest.getDifficulty());
        quiz.setStartDate(quizRequest.getStartDate());
        quiz.setEndDate(quizRequest.getEndDate());
        quiz.setMinimumPassingScore(quizRequest.getMinimumPassingScore());

        Quiz savedQuiz = saveQuiz(quiz);
        questionService.fetchAndSaveQuestions(quizRequest, savedQuiz);
        sendNewQuizNotifications(savedQuiz);

        return savedQuiz;
    }

    private void validateQuizTiming(Date startDate, Date endDate) {
        if (startDate == null) {
            throw new IllegalArgumentException("Quiz start date cannot be null");
        }
        if (endDate == null) {
            throw new IllegalArgumentException("Quiz end date cannot be null");
        }

        Date currentDate = new Date();
        if (startDate.before(getCurrentDateWithoutTime())) {
            throw new IllegalArgumentException("Quiz start date cannot be in the past");
        }
        if (endDate.before(startDate) || endDate.equals(startDate)) {
            throw new IllegalArgumentException("Quiz end date must be after start date");
        }

        long durationMillis = endDate.getTime() - startDate.getTime();
        long oneHour = 60 * 60 * 1000;
        long thirtyDays = 30L * 24 * 60 * 60 * 1000;

        if (durationMillis < oneHour) {
            throw new IllegalArgumentException("Quiz duration must be at least 1 hour");
        }
        if (durationMillis > thirtyDays) {
            throw new IllegalArgumentException("Quiz duration cannot exceed 30 days");
        }
    }

    private Date getCurrentDateWithoutTime() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private void sendNewQuizNotifications(Quiz quiz) {
        try {
            List<User> allUsers = userRepository.findAll();
            List<User> nonAdminUsers = allUsers.stream()
                    .filter(user -> !"ADMIN".equals(user.getRole()))
                    .collect(Collectors.toList());

            for (User user : nonAdminUsers) {
                emailService.sendQuizNotification(user.getEmail(), quiz.getName());
                System.out.println("Email notification sent to: " + user.getEmail() + " for quiz: " + quiz.getName());
            }
        } catch (Exception e) {
            System.err.println("Error sending email notifications: " + e.getMessage());
        }
    }

    private Quiz saveQuiz(Quiz quiz) {
        return quizRepository.save(quiz);
    }

    public Map<String, Object> canUserParticipateInQuiz(Long quizId, Long userId) {
        Map<String, Object> result = new HashMap<>();

        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            Quiz quiz = quizRepository.findById(quizId)
                    .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

            Date currentDate = new Date();
            if (quiz.getStartDate() == null || quiz.getEndDate() == null) {
                result.put("canParticipate", false);
                result.put("reason", "Quiz dates not properly configured");
                return result;
            }

            boolean isBeforeStart = currentDate.before(quiz.getStartDate());
            boolean isAfterEnd = currentDate.after(quiz.getEndDate());

            if (isBeforeStart) {
                result.put("canParticipate", false);
                result.put("reason", "Quiz has not started yet");
                result.put("startsAt", quiz.getStartDate());
                return result;
            }

            if (isAfterEnd) {
                result.put("canParticipate", false);
                result.put("reason", "Quiz has already ended");
                result.put("endedAt", quiz.getEndDate());
                return result;
            }

            boolean hasParticipated = quiz.getParticipations().stream()
                    .anyMatch(participation -> participation.getUser().getId().equals(userId));

            if (hasParticipated) {
                result.put("canParticipate", false);
                result.put("reason", "You have already participated in this quiz");
                return result;
            }

            if (quiz.getQuestions() == null || quiz.getQuestions().isEmpty()) {
                result.put("canParticipate", false);
                result.put("reason", "Quiz has no questions available");
                return result;
            }

            result.put("canParticipate", true);
            result.put("reason", "You can participate in this quiz");
            result.put("questionsCount", quiz.getQuestions().size());

        } catch (Exception e) {
            result.put("canParticipate", false);
            result.put("reason", "Error checking participation eligibility: " + e.getMessage());
        }

        return result;
    }

    public Map<String, Object> getQuizStatus(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

        Map<String, Object> status = new HashMap<>();
        Date currentDate = new Date();

        status.put("quizId", quiz.getId());
        status.put("quizName", quiz.getName());
        status.put("startDate", quiz.getStartDate());
        status.put("endDate", quiz.getEndDate());
        status.put("currentDate", currentDate);

        if (currentDate.before(quiz.getStartDate())) {
            status.put("state", "UPCOMING");
            long timeToStart = quiz.getStartDate().getTime() - currentDate.getTime();
            status.put("timeToStart", timeToStart);
        } else if (currentDate.after(quiz.getEndDate())) {
            status.put("state", "ENDED");
            long timeSinceEnd = currentDate.getTime() - quiz.getEndDate().getTime();
            status.put("timeSinceEnd", timeSinceEnd);
        } else {
            status.put("state", "ACTIVE");
            long timeToEnd = quiz.getEndDate().getTime() - currentDate.getTime();
            status.put("timeToEnd", timeToEnd);
        }

        int totalParticipants = quiz.getParticipations().size();
        status.put("totalParticipants", totalParticipants);
        status.put("totalQuestions", quiz.getQuestions().size());

        return status;
    }

    public Map<String, Object> getUserParticipationStatus(Long quizId, Long userId) {
        Map<String, Object> status = new HashMap<>();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

        status.put("userId", userId);
        status.put("username", user.getUsername());
        status.put("quizId", quizId);
        status.put("quizName", quiz.getName());

        Optional<Participation> participation = quiz.getParticipations().stream()
                .filter(p -> p.getUser().getId().equals(userId))
                .findFirst();

        if (participation.isPresent()) {
            status.put("hasParticipated", true);
            status.put("participationDate", new Date());

            List<Score> userScores = scoreRepository.findByUserId(userId);
            Optional<Score> score = userScores.stream()
                    .filter(s -> s.getQuiz().getId().equals(quizId))
                    .findFirst();

            if (score.isPresent()) {
                status.put("hasSubmitted", true);
                status.put("score", score.get().getScore());
                status.put("completedDate", score.get().getCompletedDate());
            } else {
                status.put("hasSubmitted", false);
            }
        } else {
            status.put("hasParticipated", false);
            status.put("hasSubmitted", false);
        }

        Map<String, Object> eligibility = canUserParticipateInQuiz(quizId, userId);
        status.put("eligibility", eligibility);

        return status;
    }

    public Quiz updateQuiz(Long id, QuizRequest updatedquizRequest) {
        Optional<Quiz> quizOptional = quizRepository.findById(id);
        if (quizOptional.isPresent()) {
            Quiz quiz = quizOptional.get();

            Date currentDate = new Date();
            boolean isActive = !currentDate.before(quiz.getStartDate()) && !currentDate.after(quiz.getEndDate());

            if (updatedquizRequest.getName() != null) {
                quiz.setName(updatedquizRequest.getName());
            }

            Date newStartDate = updatedquizRequest.getStartDate() != null ? updatedquizRequest.getStartDate() : quiz.getStartDate();
            Date newEndDate = updatedquizRequest.getEndDate() != null ? updatedquizRequest.getEndDate() : quiz.getEndDate();

            if (isActive && updatedquizRequest.getStartDate() != null) {
                throw new IllegalArgumentException("Cannot change start date of an active quiz");
            }

            validateQuizTiming(newStartDate, newEndDate);

            if (updatedquizRequest.getStartDate() != null) {
                quiz.setStartDate(updatedquizRequest.getStartDate());
            }
            if (updatedquizRequest.getEndDate() != null) {
                quiz.setEndDate(updatedquizRequest.getEndDate());
            }
            if (updatedquizRequest.getMinimumPassingScore() != null) {
                quiz.setMinimumPassingScore(updatedquizRequest.getMinimumPassingScore());
            }

            return quizRepository.save(quiz);
        }
        return null;
    }

    public List<Quiz> getAllQuizzes() {
        return quizRepository.findAll();
    }

    public Optional<Quiz> getQuizById(Long id) {
        return quizRepository.findById(id);
    }

    public Quiz getQuizByName(String name) {
        return quizRepository.findByName(name).orElse(null);
    }

    public boolean deleteQuiz(Long id) {
        if (quizRepository.existsById(id)) {
            quizRepository.deleteById(id);
            return true;
        }
        return false;
    }

    public boolean createCategory(String categoryName) {
        return QuizCategoryMapper.addCategory(categoryName);
    }

    public boolean deleteCategory(String categoryName) {
        return QuizCategoryMapper.deleteCategory(categoryName);
    }

    public List<Quiz> getOngoingQuizzes() {
        Date currentDate = new Date();
        return quizRepository.findAll().stream()
                .filter(quiz -> quiz.getStartDate() != null && quiz.getEndDate() != null)
                .filter(quiz -> !currentDate.before(quiz.getStartDate()) && !currentDate.after(quiz.getEndDate()))
                .collect(Collectors.toList());
    }

    public List<Quiz> getUpcomingQuizzes() {
        Date currentDate = new Date();
        return quizRepository.findAll().stream()
                .filter(quiz -> quiz.getStartDate() != null)
                .filter(quiz -> currentDate.before(quiz.getStartDate()))
                .collect(Collectors.toList());
    }
    public List<Quiz> getPastQuizzes() {
        Date currentDate = new Date();
        return quizRepository.findAll().stream()
                .filter(quiz -> quiz.getEndDate() != null)
                .filter(quiz -> currentDate.after(quiz.getEndDate()))
                .collect(Collectors.toList());
    }

    public List<Quiz> getParticipatedQuizzes(Long userId) {
        return quizRepository.findParticipatedQuizzesById(userId);
    }

    public List<Question> playQuiz(Long quizId, Long userId) {
        try {
            System.out.println("Fetching user with ID: " + userId);
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            System.out.println("Fetching quiz with ID: " + quizId);
            Quiz quiz = quizRepository.findById(quizId)
                    .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

            // ENHANCED - Use comprehensive participation check
            Map<String, Object> eligibility = canUserParticipateInQuiz(quizId, userId);
            if (!(Boolean) eligibility.get("canParticipate")) {
                throw new IllegalStateException((String) eligibility.get("reason"));
            }

            // Record the participation (mark the user as participating)
            System.out.println("Recording participation.");
            Participation participation = new Participation(user, quiz);
            participationRepository.save(participation);

            System.out.println("Fetching questions for quiz.");
            List<Question> questions = questionRepository.findByQuizId(quizId);
            if (questions == null || questions.isEmpty()) {
                throw new IllegalStateException("No questions found for this quiz.");
            }

            // Create a list of questions with correct answers hidden
            return questions.stream()
                    .map(question -> {
                        Question sanitizedQuestion = new Question();
                        sanitizedQuestion.setId(question.getId());
                        sanitizedQuestion.setQuestionText(question.getQuestionText());
                        sanitizedQuestion.setOptions(question.getOptions());
                        sanitizedQuestion.setCorrectAnswer(null); // Hide correct answer
                        return sanitizedQuestion;
                    })
                    .limit(10) // Limit to 10 questions
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace(); // Log the exception stack trace
            throw new RuntimeException("An unexpected error occurred.", e); // Re-throw exception with more details
        }
    }

    public Map<String, Object> submitAnswers(Long quizId, Long userId, Map<Long, String> answers) {
        // Fetch user and quiz from the repositories
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

        // ENHANCED - Verify quiz is still active when submitting
        Date currentDate = new Date();
        if (currentDate.after(quiz.getEndDate())) {
            throw new IllegalStateException("Cannot submit answers - quiz has ended");
        }

        // Prepare variables to track score and feedback
        int correctAnswersCount = 0;
        StringBuilder feedbackBuilder = new StringBuilder();

        // Iterate through all the questions in the quiz
        for (Question currentQuestion : quiz.getQuestions()) {
            // Get the answer from the provided map (answers) using the question's ID
            String userAnswer = answers.get(currentQuestion.getId());

            if (userAnswer != null) {
                // Validate the player's answer
                boolean isCorrect = currentQuestion.getCorrectAnswer().equalsIgnoreCase(userAnswer);

                // Update correct answer count
                if (isCorrect) {
                    correctAnswersCount++;
                }

                // Generate feedback for the current question
                if (isCorrect) {
                    feedbackBuilder.append("Question ").append(currentQuestion.getId())
                            .append(": Correct! Well done. ");
                } else {
                    feedbackBuilder.append("Question ").append(currentQuestion.getId())
                            .append(": Incorrect. The correct answer is: ")
                            .append(currentQuestion.getCorrectAnswer()).append(". ");
                }
            } else {
                // If the user did not answer the question, provide feedback
                feedbackBuilder.append("Question ").append(currentQuestion.getId())
                        .append(": No answer provided. ");
            }
        }

        // Calculate the total score out of 10
        int totalQuestions = quiz.getQuestions().size();
        double score = ((double) correctAnswersCount / totalQuestions) * 10;

        // Check if user passed based on minimum passing score
        boolean passed = false;
        if (quiz.getMinimumPassingScore() != null) {
            double percentage = ((double) correctAnswersCount / totalQuestions) * 100;
            passed = percentage >= quiz.getMinimumPassingScore();
        }

        // Store the score in the database
        Score quizScore = new Score();
        quizScore.setUser(user);
        quizScore.setQuiz(quiz);
        quizScore.setScore(score);
        scoreRepository.save(quizScore);

        // Return response with total score out of 10 and feedback
        Map<String, Object> response = new HashMap<>();
        response.put("correctAnswers", correctAnswersCount);
        response.put("score", score);  // Returning score out of 10
        response.put("feedback", feedbackBuilder.toString());
        response.put("passed", passed);
        response.put("minimumPassingScore", quiz.getMinimumPassingScore());

        return response;
    }

    public Map<String, Object> replayQuiz(Long quizId, Long userId, Map<Long, String> playerAnswers) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

        // ENHANCED - Use comprehensive timing check
        Map<String, Object> eligibility = canUserParticipateInQuiz(quizId, userId);
        if (!(Boolean) eligibility.get("canParticipate")) {
            // For replay, we might allow it even if quiz ended, but with warning
            Date currentDate = new Date();
            if (currentDate.after(quiz.getEndDate())) {
                System.out.println("Warning: Replaying quiz that has ended");
            } else {
                throw new IllegalStateException((String) eligibility.get("reason"));
            }
        }

        // Validate player answers
        if (playerAnswers == null || playerAnswers.isEmpty()) {
            throw new IllegalArgumentException("Player answers are missing.");
        }

        // Validate all questions are answered
        for (Question question : quiz.getQuestions()) {
            if (!playerAnswers.containsKey(question.getId())) {
                throw new IllegalArgumentException("Answer missing for question ID: " + question.getId());
            }
        }

        // Calculate score and feedback
        int correctAnswersCount = 0;
        List<String> feedbackList = new ArrayList<>();

        for (Question question : quiz.getQuestions()) {
            String userAnswer = playerAnswers.get(question.getId());
            String correctAnswer = question.getCorrectAnswer();

            if (correctAnswer == null) {
                feedbackList.add("Question " + question.getId() + ": No correct answer provided.");
                continue;
            }

            if (correctAnswer.equalsIgnoreCase(userAnswer)) {
                correctAnswersCount++;
                feedbackList.add("Question " + question.getId() + ": Correct!");
            } else {
                feedbackList.add("Question " + question.getId() + ": Incorrect. Correct answer: " + correctAnswer);
            }
        }

        // Avoid divide-by-zero in score calculation
        int totalQuestions = quiz.getQuestions().size();
        double score = totalQuestions > 0 ? ((double) correctAnswersCount / totalQuestions) * 10 : 0;

        // Log replay action
        System.out.println("Player " + userId + " replayed Quiz " + quizId + " with score: " + score);

        // Return score and feedback
        Map<String, Object> response = new HashMap<>();
        response.put("score", score);
        response.put("feedback", feedbackList);

        return response;
    }

    public void addRating(Long quizId, int rating) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

        // Update rating logic
        double totalRating = quiz.getRating() * quiz.getRatingCount() + rating;
        quiz.setRatingCount(quiz.getRatingCount() + 1);
        quiz.setRating(totalRating / quiz.getRatingCount());

        // Save updated quiz
        quizRepository.save(quiz);
    }

    public List<Map<String, Object>> getUserQuizHistory(Long userId) {
        List<Score> userScores = scoreRepository.findByUserId(userId);
        return userScores.stream().map(score -> {
            Map<String, Object> history = new HashMap<>();
            history.put("quizId", score.getQuiz().getId());
            history.put("quizName", score.getQuiz().getName());
            history.put("score", score.getScore());
            history.put("completedDate", score.getCompletedDate());
            return history;
        }).collect(Collectors.toList());
    }
}
