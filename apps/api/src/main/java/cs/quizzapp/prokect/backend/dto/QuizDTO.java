package cs.quizzapp.prokect.backend.dto;

import java.util.Date;
import java.util.List;

public class QuizDTO {
    private Long id;
    private String name;
    private String category;
    private String difficulty;
    private Date startDate;
    private Date endDate;
    private Double minimumPassingScore; // New field
    private List<QuestionDTO> questions;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public Double getMinimumPassingScore() {
        return minimumPassingScore;
    }

    public void setMinimumPassingScore(Double minimumPassingScore) {
        this.minimumPassingScore = minimumPassingScore;
    }

    public List<QuestionDTO> getQuestions() {
        return questions;
    }

    public void setQuestions(List<QuestionDTO> questions) {
        this.questions = questions;
    }
}