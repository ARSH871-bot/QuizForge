package cs.quizzapp.prokect.backend.db;

import cs.quizzapp.prokect.backend.models.Quiz;
import cs.quizzapp.prokect.backend.models.Score;
import cs.quizzapp.prokect.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScoreRepository extends JpaRepository<Score, Long> {
    //This will get a score by user and quiz.
    Optional<Score> findByUserAndQuiz(User user, Quiz quiz);

    // Find all scores for a specific user
    List<Score> findByUserId(Long userId);

    // Find all scores for a specific quiz ordered by score descending
    List<Score> findByQuizIdOrderByScoreDesc(Long quizId);

    // Find top scores across all quizzes
    @Query("SELECT s FROM Score s ORDER BY s.score DESC")
    List<Score> findTopScores();

    // Get user's best score for a specific quiz
    @Query("SELECT s FROM Score s WHERE s.user.id = :userId AND s.quiz.id = :quizId ORDER BY s.score DESC")
    List<Score> findBestScoreByUserAndQuiz(@Param("userId") Long userId, @Param("quizId") Long quizId);

    // Get average score for a quiz
    @Query("SELECT AVG(s.score) FROM Score s WHERE s.quiz.id = :quizId")
    Double getAverageScoreForQuiz(@Param("quizId") Long quizId);
}