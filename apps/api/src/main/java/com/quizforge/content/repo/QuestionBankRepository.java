package com.quizforge.content.repo;

import com.quizforge.content.domain.QuestionBank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuestionBankRepository extends JpaRepository<QuestionBank, UUID> {
    List<QuestionBank> findByWorkspaceIdAndArchivedAtIsNull(UUID workspaceId);
    boolean existsByWorkspaceIdAndName(UUID workspaceId, String name);
}
