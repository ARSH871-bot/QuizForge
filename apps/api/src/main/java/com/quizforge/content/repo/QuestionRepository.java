package com.quizforge.content.repo;

import com.quizforge.content.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

    List<Question> findByBankIdAndSupersededByIsNullAndRetiredAtIsNull(UUID bankId);

    Optional<Question> findByBankIdAndContentHashAndSupersededByIsNull(
            UUID bankId, String contentHash);

    long countByLineageId(UUID lineageId);

    /** Every version of a question, oldest first. */
    List<Question> findByLineageIdOrderByVersionAsc(UUID lineageId);
}
