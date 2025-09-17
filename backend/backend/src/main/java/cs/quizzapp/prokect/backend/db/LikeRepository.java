package cs.quizzapp.prokect.backend.db;

import cs.quizzapp.prokect.backend.models.Like;
import cs.quizzapp.prokect.backend.models.Quiz;
import cs.quizzapp.prokect.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LikeRepository extends JpaRepository<Like, Long> {
    Optional<Like> findByUserAndQuiz(User user, Quiz quiz);
    long countByQuiz(Quiz quiz);
}