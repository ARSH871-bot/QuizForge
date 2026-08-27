package com.quizforge.content.repo;

import com.quizforge.content.domain.QuestionBank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.domain.Limit;

import java.util.UUID;

public interface QuestionBankRepository extends JpaRepository<QuestionBank, UUID> {
    List<QuestionBank> findByWorkspaceIdAndArchivedAtIsNull(UUID workspaceId);

    List<QuestionBank> findByWorkspaceIdAndArchivedAtIsNullOrderByIdDesc(
            UUID workspaceId, Limit limit);

    List<QuestionBank> findByWorkspaceIdAndArchivedAtIsNullAndIdLessThanOrderByIdDesc(
            UUID workspaceId, UUID after, Limit limit);
    boolean existsByWorkspaceIdAndName(UUID workspaceId, String name);
}
