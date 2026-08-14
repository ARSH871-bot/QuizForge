package com.quizforge.play.repo;

import com.quizforge.play.domain.Response;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResponseRepository extends JpaRepository<Response, UUID> {

    Optional<Response> findByAttemptIdAndQuestionId(UUID attemptId, UUID questionId);

    List<Response> findByAttemptId(UUID attemptId);

    long countByAttemptIdAndCorrectIsTrue(UUID attemptId);
}
