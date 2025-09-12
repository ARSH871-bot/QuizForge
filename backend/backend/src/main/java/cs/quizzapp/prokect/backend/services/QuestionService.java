package cs.quizzapp.prokect.backend.services;

import cs.quizzapp.prokect.backend.db.QuestionRepository;
import cs.quizzapp.prokect.backend.models.Question;
import cs.quizzapp.prokect.backend.models.Quiz;
import cs.quizzapp.prokect.backend.payload.QuizRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class QuestionService {

    @Autowired
    private QuestionRepository questionRepository;

    /**
     * Fetches all questions from the database.
     * @return List of all questions.
     */
    public List<Question> getAllQuestions() {
        return questionRepository.findAll();
    }

    /**
     * Fetches a specific question by its ID.
     * @param id The ID of the question.
     * @return An optional containing the question if found.
     */
    public Optional<Question> getQuestionById(Long id) {
        return questionRepository.findById(id);
    }

    /**
     * Fetches all questions associated with a specific quiz by its ID.
     * @param quizId The ID of the quiz.
     * @return List of questions for the quiz.
     */
    public List<Question> getQuestionsByQuizId(Long quizId) {
        return questionRepository.findByQuizId(quizId);
    }

    /**
     * Adds a new question to the database.
     * @param question The question to add.
     * @return The saved question.
     */
    public Question addQuestion(Question question) {
        return questionRepository.save(question);
    }

    /**
     * Updates an existing question by ID.
     * @param id The ID of the question to update.
     * @param updatedQuestion The updated question data.
     * @return An optional containing the updated question if successful.
     */
    public Optional<Question> updateQuestion(Long id, Question updatedQuestion) {
        return questionRepository.findById(id).map(existingQuestion -> {
            existingQuestion.setQuestionText(updatedQuestion.getQuestionText());
            existingQuestion.setCorrectAnswer(updatedQuestion.getCorrectAnswer());
            existingQuestion.setOptions(updatedQuestion.getOptions());
            return questionRepository.save(existingQuestion);
        });
    }

    /**
     * Deletes a specific question by its ID.
     * @param id The ID of the question to delete.
     * @return true if the question was deleted, false otherwise.
     */
    public boolean deleteQuestion(Long id) {
        if (questionRepository.existsById(id)) {
            questionRepository.deleteById(id);
            return true;
        }
        return false;
    }


    /*/**
     * Fetches questions for a quiz. If questions already exist for the quiz, they are limited to 10 and returned.
     * Otherwise, new questions are fetched from OpenTDB and saved to the database.
     *
     * @param quizRequest Contains admin input like category, difficulty, and amount.
     * @param quiz        The quiz to which the questions belong.
     * @return List of saved or existing questions.
     */
    public List<Question> fetchAndSaveQuestions(QuizRequest quizRequest, Quiz quiz) {
        // Check if questions already exist for the quiz
        List<Question> existingQuestions = getQuestionsByQuizId(quiz.getId());
        if (!existingQuestions.isEmpty()) {
            System.out.println("Returning up to 10 existing questions for quiz ID: " + quiz.getId());

            // Limit the number of questions to 10
            return existingQuestions.stream()
                    .limit(10)
                    .toList(); // Return only the first 10 questions
        }
        // Initialize fetchedQuestions list
        List<Question> fetchedQuestions = new ArrayList<>();
        // Build the dynamic URL
        String apiUrl = "https://opentdb.com/api.php?amount=" + quizRequest.getAmount() +
                "&category=" + quizRequest.getCategoryId() +
                "&difficulty=" + quizRequest.getDifficulty() +
                "&type=multiple";

        RestTemplate restTemplate = new RestTemplate();
        List<Question> questions = new ArrayList<>();

        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                // Fetch data from OpenTDB API
                Map<String, Object> response = restTemplate.getForObject(apiUrl, Map.class);

                if (response != null && response.containsKey("results")) {
                    List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

                    for (Map<String, Object> result : results) {
                        // Map OpenTDB data to Question entity
                        Question newQuestion = new Question();
                        newQuestion.setQuestionText((String) result.get("question"));
                        newQuestion.setCorrectAnswer((String) result.get("correct_answer"));

                        // Combine correct and incorrect answers into options
                        List<String> options = new ArrayList<>((List<String>) result.get("incorrect_answers"));
                        options.add(newQuestion.getCorrectAnswer());
                        Collections.shuffle(options);

                        newQuestion.setOptions(options);
                        newQuestion.setQuiz(quiz);
                        // Check for duplicate questions in fetchedQuestions
                        boolean isDuplicate = fetchedQuestions.stream()
                                .anyMatch(q -> q.getQuestionText().equals(newQuestion.getQuestionText()));
                        if (isDuplicate) {
                            System.out.println("Skipping duplicate question: " + newQuestion.getQuestionText());
                            continue; // Skip saving this question
                        }

                        // Save question to database
                        fetchedQuestions.add(questionRepository.save(newQuestion));
                    }

                    return fetchedQuestions; // Exit loop if successful
                }

                throw new RuntimeException("Invalid response from OpenTDB API: " + response);

            } catch (Exception e) {
                attempt++;
                System.err.println("Attempt " + attempt + " failed: " + e.getMessage());

                if (attempt >= maxRetries) {
                    throw new RuntimeException("Failed to fetch questions after " + maxRetries + " attempts", e);
                }

                // Wait before retrying (e.g., 2 seconds)
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt(); // Restore interrupted status
                }
            }
        }

        return fetchedQuestions;
    }
}