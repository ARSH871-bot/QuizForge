package cs.quizzapp.prokect.backend.dto;

import java.util.List;

public class QuestionDTO {
    private Long id;
    private String questionText;
    private List<String> options;
    private String correctAnswer;
    private String type; // New field: "multiple" or "boolean"
    private String difficulty; // New field: question-specific difficulty
    private Integer questionNumber; // New field: question position in quiz
    private Boolean isAnswered; // New field: track if user has answered this question

    // Constructors
    public QuestionDTO() {}

    public QuestionDTO(Long id, String questionText, List<String> options, String correctAnswer) {
        this.id = id;
        this.questionText = questionText;
        this.options = options;
        this.correctAnswer = correctAnswer;
    }

    public QuestionDTO(Long id, String questionText, List<String> options, String correctAnswer, String type, String difficulty, Integer questionNumber) {
        this.id = id;
        this.questionText = questionText;
        this.options = options;
        this.correctAnswer = correctAnswer;
        this.type = type;
        this.difficulty = difficulty;
        this.questionNumber = questionNumber;
        this.isAnswered = false;
    }

    // Existing getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    // New getters and setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public Integer getQuestionNumber() {
        return questionNumber;
    }

    public void setQuestionNumber(Integer questionNumber) {
        this.questionNumber = questionNumber;
    }

    public Boolean getIsAnswered() {
        return isAnswered;
    }

    public void setIsAnswered(Boolean isAnswered) {
        this.isAnswered = isAnswered;
    }
}