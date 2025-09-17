package cs.quizzapp.prokect.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "quiz_likes", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "quiz_id"})
})
public class Like {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    private LocalDateTime likedAt = LocalDateTime.now();

    public Like() {}

    public Like(User user, Quiz quiz) {
        this.user = user;
        this.quiz = quiz;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Quiz getQuiz() { return quiz; }
    public void setQuiz(Quiz quiz) { this.quiz = quiz; }
    public LocalDateTime getLikedAt() { return likedAt; }
    public void setLikedAt(LocalDateTime likedAt) { this.likedAt = likedAt; }
}