package cs.quizzapp.prokect.backend.db;

import cs.quizzapp.prokect.backend.models.Participation;
import cs.quizzapp.prokect.backend.models.Quiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParticipationRepository extends JpaRepository<Participation, Long> {
    List<Participation> findByUserIdAndQuizId(Long userId, Long quizId);
}
