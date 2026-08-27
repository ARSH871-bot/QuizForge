package com.quizforge.content.repo;

import com.quizforge.content.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;

import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

    List<Question> findByBankIdAndSupersededByIsNullAndRetiredAtIsNull(UUID bankId);

    List<Question> findByBankIdAndSupersededByIsNullAndRetiredAtIsNullOrderByIdDesc(
            UUID bankId, Limit limit);

    List<Question> findByBankIdAndSupersededByIsNullAndRetiredAtIsNullAndIdLessThanOrderByIdDesc(
            UUID bankId, UUID after, Limit limit);

    Optional<Question> findByBankIdAndContentHashAndSupersededByIsNull(
            UUID bankId, String contentHash);

    long countByLineageId(UUID lineageId);

    /** Every version of a question, oldest first. */
    List<Question> findByLineageIdOrderByVersionAsc(UUID lineageId);

    /**
     * A lineage page, oldest first — the one collection paged ascending, because
     * version order is the useful order for a history.
     *
     * <p>Identifiers are UUIDv7, so ascending by id is ascending by version.
     */
    List<Question> findByLineageIdOrderByIdAsc(UUID lineageId, Limit limit);

    List<Question> findByLineageIdAndIdGreaterThanOrderByIdAsc(
            UUID lineageId, UUID after, Limit limit);
}
