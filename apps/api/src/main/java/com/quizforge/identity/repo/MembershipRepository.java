package com.quizforge.identity.repo;

import com.quizforge.identity.domain.Membership;
import com.quizforge.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByWorkspaceIdAndAccountId(UUID workspaceId, UUID accountId);

    List<Membership> findByAccountId(UUID accountId);

    List<Membership> findByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndRole(UUID workspaceId, Role role);
}
