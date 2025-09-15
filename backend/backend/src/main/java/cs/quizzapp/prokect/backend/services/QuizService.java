package cs.quizzapp.prokect.backend.services;

import cs.quizzapp.prokect.backend.db.*;
import cs.quizzapp.prokect.backend.models.*;
import cs.quizzapp.prokect.backend.payload.QuizRequest;
import cs.quizzapp.prokect.backend.utils.QuizCategoryMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

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

    private void sendNewQuizNotifications(Quiz quiz) {
        try {
            // Get all users except admin
            List<User> allUsers = userRepository.findAll();
            List<User> nonAdminUsers = allUsers.stream()
                    .filter(user -> !"ADMIN".equals(user.getRole()))
                    .toList();

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

    public Quiz updateQuiz(Long id, QuizRequest updatedquizRequest) {
        Optional<Quiz> quizOptional = quizRepository.findById(id);
        if (quizOptional.isPresent()) {
            Quiz quiz = quizOptional.get();
            if (updatedquizRequest.getName() != null) {
                quiz.setName(updatedquizRequest.getName());
            }
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

    //Get ongoing or currently active quizzes.
    public List<Quiz> getOngoingQuizzes() {
        return quizRepository.findOngoingQuizzes(new Date());
    }

    //Get upcoming quizzes.
    public List<Quiz> getUpcomingQuizzes() {
        return quizRepository.findUpcomingQuizzes(new Date());
    }

    //Get past quizzes.
    public List<Quiz> getPastQuizzes() {
        return quizRepository.findPastQuizzes(new Date());
    }

    //Get participated quizzes.
    public List<Quiz> getParticipatedQuizzes(Long userId) {
        return quizRepository.findParticipatedQuizzesById(userId);
    }

    //Add the players who are participating in the same quiz with same 10 questions.
    public List<Question> playQuiz(Long quizId, Long userId) {
        try {
            System.out.println("Fetching user with ID: " + userId);
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            System.out.println("Fetching quiz with ID: " + quizId);
            Quiz quiz = quizRepository.findById(quizId)
                    .orElseThrow(() -> new IllegalArgumentException("Quiz not found"));

            if (quiz.getStartDate() == null || quiz.getEndDate() == null) {
                throw new IllegalStateException("Quiz start or end date is not set.");
            }

            Date currentDate = new Date();
            boolean isOngoing = !currentDate.before(quiz.getStartDate()) && !currentDate.after(quiz.getEndDate());
            if (!isOngoing) {
                throw new IllegalStateException("Quiz is no longer active.");
            }

            System.out.println("Checking if user has already participated.");
            boolean hasParticipated = quiz.getParticipations().stream()
                    .anyMatch(participation -> participation.getUser().getId().equals(userId));
            if (hasParticipated) {
                throw new IllegalStateException("Player has already participated in this quiz.");
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
                    .toList();
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

        // Ensure th e quiz is ongoing
        Date currentDate = new Date();
        if (currentDate.before(quiz.getStartDate()) || currentDate.after(quiz.getEndDate())) {
            throw new IllegalStateException("Quiz is no longer active.");
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
        }).toList();
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
        }).toList();
    }
}