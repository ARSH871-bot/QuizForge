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

    @Autowired
    private EmailService emailService;

    public QuizService(ParticipationRepository participationRepository, QuizRepository quizRepository, UserRepository userRepository, QuestionService questionService, ScoreRepository scoreRepository, QuestionRepository questionRepository) {
        this.participationRepository = participationRepository;
        this.quizRepository = quizRepository;
        this.userRepository = userRepository;
        this.questionService = questionService;
        this.scoreRepository = scoreRepository;
        this.questionRepository = questionRepository;
    }

    public Quiz createQuizWithQuestions(QuizRequest quizRequest) {
        // ENHANCED - Validate quiz timing before creation
        validateQuizTiming(quizRequest.getStartDate(), quizRequest.getEndDate());

        // Map category name to ID using QuizCategoryMapper
        Integer categoryId = QuizCategoryMapper.getCategoryId(quizRequest.getCategory());
        if (categoryId == null) {
            throw new IllegalArgumentException("Invalid category name provided: " + quizRequest.getCategory());
        }

        // Create a new Quiz object
        Quiz quiz = new Quiz();
        quiz.setName(quizRequest.getName());
        quiz.setCategory(quizRequest.getCategory());
        quiz.setDifficulty(quizRequest.getDifficulty());
        quiz.setStartDate(quizRequest.getStartDate());
        quiz.setEndDate(quizRequest.getEndDate());
        quiz.setMinimumPassingScore(quizRequest.getMinimumPassingScore()); // New field

        // Save the Quiz
        Quiz savedQuiz = saveQuiz(quiz);

        // Fetch and save questions for the quiz using the QuestionService
        questionService.fetchAndSaveQuestions(quizRequest, savedQuiz);

        // Send email notifications to all non-admin users
        sendNewQuizNotifications(savedQuiz);

        return savedQuiz;
    }

    // NEW METHOD - Validate quiz timing logic
    private void validateQuizTiming(Date startDate, Date endDate) {
        if (startDate == null) {
            throw new IllegalArgumentException("Quiz start date cannot be null");
        }
        if (endDate == null) {
            throw new IllegalArgumentException("Quiz end date cannot be null");
        }

        Date currentDate = new Date();

        // Start date cannot be in the past (allow same day)
        if (startDate.before(getCurrentDateWithoutTime())) {
            throw new IllegalArgumentException("Quiz start date cannot be in the past");
        }

        // End date must be after start date
        if (endDate.before(startDate) || endDate.equals(startDate)) {
            throw new IllegalArgumentException("Quiz end date must be after start date");
        }

        // Quiz duration should be reasonable (at least 1 hour, max 30 days)
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

    // NEW METHOD - Get current date without time for comparison
    private Date getCurrentDateWithoutTime() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    // NEW METHOD - Check if user can participate in quiz
    public Map<String, Object> canUserParticipateInQuiz(Long quizId, Long userId) {
        Map<String, Object> result = new HashMap<>();

        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            Quiz quiz = quizRepository.findById(quizId)
                    .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

            Date currentDate = new Date();

            // Check if quiz has proper dates
            if (quiz.getStartDate() == null || quiz.getEndDate() == null) {
                result.put("canParticipate", false);
                result.put("reason", "Quiz dates not properly configured");
                return result;
            }

            // Check if quiz is active (current time is between start and end)
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

            // Check if user has already participated
            boolean hasParticipated = quiz.getParticipations().stream()
                    .anyMatch(participation -> participation.getUser().getId().equals(userId));

            if (hasParticipated) {
                result.put("canParticipate", false);
                result.put("reason", "You have already participated in this quiz");
                return result;
            }

            // Check if quiz has questions
            if (quiz.getQuestions() == null || quiz.getQuestions().isEmpty()) {
                result.put("canParticipate", false);
                result.put("reason", "Quiz has no questions available");
                return result;
            }

            // All checks passed
            result.put("canParticipate", true);
            result.put("reason", "You can participate in this quiz");
            result.put("questionsCount", quiz.getQuestions().size());

        } catch (Exception e) {
            result.put("canParticipate", false);
            result.put("reason", "Error checking participation eligibility: " + e.getMessage());
        }

        return result;
    }

    // NEW METHOD - Get quiz status for admin monitoring
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

        // Determine quiz state
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

        // Participation statistics
        int totalParticipants = quiz.getParticipations().size();
        status.put("totalParticipants", totalParticipants);
        status.put("totalQuestions", quiz.getQuestions().size());

        return status;
    }

    // NEW METHOD - Get detailed user participation status
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

        // Check participation
        Optional<Participation> participation = quiz.getParticipations().stream()
                .filter(p -> p.getUser().getId().equals(userId))
                .findFirst();

        if (participation.isPresent()) {
            status.put("hasParticipated", true);
            // Use creation date or current date as participation date
            status.put("participationDate", new Date());

            // Check if user has submitted answers (has a score)
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

        // Add eligibility check
        Map<String, Object> eligibility = canUserParticipateInQuiz(quizId, userId);
        status.put("eligibility", eligibility);

        return status;
    }

    private void sendNewQuizNotifications(Quiz quiz) {
        try {
            // Get all users except admin
            List<User> allUsers = userRepository.findAll();
            List<User> nonAdminUsers = allUsers.stream()
                    .filter(user -> !"ADMIN".equals(user.getRole()))
                    .collect(Collectors.toList());

            // Send notification to each non-admin user
            for (User user : nonAdminUsers) {
                emailService.sendQuizNotification(user.getEmail(), quiz.getName());
                System.out.println("Email notification sent to: " + user.getEmail() + " for quiz: " + quiz.getName());
            }
        } catch (Exception e) {
            System.err.println("Error sending email notifications: " + e.getMessage());
            // Don't fail quiz creation if email sending fails
        }
    }

    private Quiz saveQuiz(Quiz quiz) {
        return quizRepository.save(quiz);
    }

    // ENHANCED - Update quiz with timing validation
    public Quiz updateQuiz(Long id, QuizRequest updatedquizRequest) {
        Optional<Quiz> quizOptional = quizRepository.findById(id);
        if (quizOptional.isPresent()) {
            Quiz quiz = quizOptional.get();

            // Check if quiz is currently active - prevent certain updates
            Date currentDate = new Date();
            boolean isActive = !currentDate.before(quiz.getStartDate()) && !currentDate.after(quiz.getEndDate());

            if (updatedquizRequest.getName() != null) {
                quiz.setName(updatedquizRequest.getName());
            }

            // Validate timing changes
            Date newStartDate = updatedquizRequest.getStartDate() != null ? updatedquizRequest.getStartDate() : quiz.getStartDate();
            Date newEndDate = updatedquizRequest.getEndDate() != null ? updatedquizRequest.getEndDate() : quiz.getEndDate();

            // If quiz is active, don't allow start date changes
            if (isActive && updatedquizRequest.getStartDate() != null) {
                throw new IllegalArgumentException("Cannot change start date of an active quiz");
            }

            // Validate new timing
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

    // ENHANCED - Get ongoing quizzes with better validation
    public List<Quiz> getOngoingQuizzes() {
        Date currentDate = new Date();
        return quizRepository.findAll().stream()
                .filter(quiz -> quiz.getStartDate() != null && quiz.getEndDate() != null)
                .filter(quiz -> !currentDate.before(quiz.getStartDate()) && !currentDate.after(quiz.getEndDate()))
                .collect(Collectors.toList());
    }

    // ENHANCED - Get upcoming quizzes with better validation
    public List<Quiz> getUpcomingQuizzes() {
        Date currentDate = new Date();
        return quizRepository.findAll().stream()
                .filter(quiz -> quiz.getStartDate() != null)
                .filter(quiz -> currentDate.before(quiz.getStartDate()))
                .collect(Collectors.toList());
    }

    // ENHANCED - Get past quizzes with better validation
    public List<Quiz> getPastQuizzes() {
        Date currentDate = new Date();
        return quizRepository.findAll().stream()
                .filter(quiz -> quiz.getEndDate() != null)
                .filter(quiz -> currentDate.after(quiz.getEndDate()))
                .collect(Collectors.toList());
    }

    //Get participated quizzes.
    public List<Quiz> getParticipatedQuizzes(Long userId) {
        return quizRepository.findParticipatedQuizzesById(userId);
    }

    // ENHANCED - Play quiz with comprehensive timing and participation checks
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

    //Display feedback according to correct and incorrect answers. Also display the score, no of answers correct.
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

    //Like a quiz
    public void likeQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));
        quiz.setLikesCount(quiz.getLikesCount() + 1);
        quizRepository.save(quiz);
    }

    //Unlike a quiz
    public void unlikeQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));
        if (quiz.getLikesCount() > 0) {
            quiz.setLikesCount(quiz.getLikesCount() - 1);
            quizRepository.save(quiz);
        }
    }

    //Additional features
    //Replay a quiz
    public Map<String, Object> replayQuiz(Long quizId, Long userId, Map<Long, String> playerAnswers) {
        // Fetch the quiz
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

    //Add a rating
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

    // New feature: Get user's quiz history
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

    // New feature: Get leaderboard for a quiz
    public List<Map<String, Object>> getQuizLeaderboard(Long quizId) {
        List<Score> scores = scoreRepository.findByQuizIdOrderByScoreDesc(quizId);
        return scores.stream().limit(10).map(score -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("username", score.getUser().getUsername());
            entry.put("score", score.getScore());
            entry.put("completedDate", score.getCompletedDate());
            return entry;
        }).collect(Collectors.toList());
    }
}