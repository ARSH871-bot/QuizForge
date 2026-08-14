package com.quizforge.play.repo;

import com.quizforge.play.domain.AttemptQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttemptQuestionRepository
        extends JpaRepository<AttemptQuestion, AttemptQuestion.Key> {

    List<AttemptQuestion> findByAttemptIdOrderByPosition(UUID attemptId);

    Optional<AttemptQuestion> findByAttemptIdAndPosition(UUID attemptId, int position);
}
