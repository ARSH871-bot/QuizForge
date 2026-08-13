package cs.quizzapp.prokect.backend.db;

import cs.quizzapp.prokect.backend.models.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


import java.util.List;
/**
 * Renamed from QuestionRepository: the new content module owns that name.
 * This interface is deleted with the rest of the legacy package in M3.
 */
@Repository
public interface LegacyQuestionRepository extends JpaRepository<Question, Long> {
    List<Question> findByQuizId(Long quizId);
}
