package cs.quizzapp.prokect.backend.db;

import cs.quizzapp.prokect.backend.models.Quiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, Long> {
    Optional<Quiz> findByName(String name);

    //This will find the ongoing or currently active quizzes when current date is between start date and end date.
    @Query("SELECT q FROM Quiz q WHERE :currentDate BETWEEN q.startDate AND q.endDate")
    List<Quiz> findOngoingQuizzes(@Param("currentDate") Date currentDate);

    //This will find the upcoming quizzes when start date is ahead of current date.
    @Query("SELECT q FROM Quiz q WHERE q.startDate > :currentDate")
    List<Quiz> findUpcomingQuizzes(@Param("currentDate") Date currentDate);

    //This will find the past quizzes when current date is ahead of end date.
    @Query("SELECT q FROM Quiz q WHERE q.endDate < :currentDate")
    List<Quiz> findPastQuizzes(@Param("currentDate") Date currentDate);

    @Query("SELECT q FROM Quiz q JOIN q.participations p WHERE p.user.id = :id")
    List<Quiz> findParticipatedQuizzesById(@Param("id") Long userId);
}
