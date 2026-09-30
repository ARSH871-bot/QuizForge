package com.quizforge.identity.repo;

import com.quizforge.identity.domain.Membership;
import com.quizforge.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;

import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByWorkspaceIdAndAccountId(UUID workspaceId, UUID accountId);

    List<Membership> findByAccountId(UUID accountId);

    /**
     * Keyset page of an account's memberships, newest first.
     *
     * <p>Ordered by primary key, which is a UUIDv7 and therefore chronological.
     * One column, unique by definition, and no tuple comparison.
     */
    List<Membership> findByAccountIdOrderByIdDesc(UUID accountId, Limit limit);

    /** The page after {@code after}. */
    List<Membership> findByAccountIdAndIdLessThanOrderByIdDesc(
            UUID accountId, UUID after, Limit limit);

    List<Membership> findByWorkspaceId(UUID workspaceId);

    List<Membership> findByWorkspaceIdOrderByIdDesc(UUID workspaceId, Limit limit);

    List<Membership> findByWorkspaceIdAndIdLessThanOrderByIdDesc(
            UUID workspaceId, UUID after, Limit limit);

    long countByWorkspaceIdAndRole(UUID workspaceId, Role role);

    /** Whether anyone in the workspace already goes by this name, ignoring case. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(m) > 0 FROM Membership m, Account a
            WHERE m.accountId = a.id AND m.workspaceId = :workspaceId
              AND LOWER(a.displayName) = LOWER(:name)
            """)
    boolean nameTakenInWorkspace(@org.springframework.data.repository.query.Param("workspaceId") UUID workspaceId,
                                 @org.springframework.data.repository.query.Param("name") String name);
}
