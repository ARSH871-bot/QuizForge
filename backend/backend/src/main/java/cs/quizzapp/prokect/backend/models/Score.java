package cs.quizzapp.prokect.backend.models;

import jakarta.persistence.*;
import java.util.Date;

@Entity
public class Score {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "completed_date")
    @Temporal(TemporalType.TIMESTAMP)
    private Date completedDate;

    // No-argument constructor required by JPA
    public Score() {
        this.completedDate = new Date(); // Set current date when created
    }

    // Parameterized constructor
    public Score(Long id, User user, Quiz quiz, double score) {
        this.id = id;
        this.user = user;
        this.quiz = quiz;
        this.score = score;
        this.completedDate = new Date();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public Date getCompletedDate() {
        return completedDate;
    }

    public void setCompletedDate(Date completedDate) {
        this.completedDate = completedDate;
    }

    @Override
    public String toString() {
        return "Score{" +
                "id=" + id +
                ", userId=" + (user != null ? user.getId() : null) +
                ", quizId=" + (quiz != null ? quiz.getId() : null) +
                ", score=" + score +
                ", completedDate=" + completedDate +
                '}';
    }
}