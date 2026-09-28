package com.quizforge.identity.app;

import com.quizforge.identity.AccountDirectory;
import com.quizforge.identity.WorkspaceEnrolment;
import com.quizforge.identity.domain.Account;
import com.quizforge.identity.domain.Membership;
import com.quizforge.identity.domain.Role;
import com.quizforge.identity.domain.Workspace;
import com.quizforge.identity.repo.AccountRepository;
import com.quizforge.identity.repo.MembershipRepository;
import com.quizforge.identity.repo.WorkspaceRepository;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Implements identity's published directory and enrolment APIs. */
@Service
public class DirectoryAdapter implements AccountDirectory, WorkspaceEnrolment {

    private final AccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final AuditService audit;

    public DirectoryAdapter(AccountRepository accounts, WorkspaceRepository workspaces,
                            MembershipRepository memberships, AuditService audit) {
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> displayNamesOf(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return accounts.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getDisplayName));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> workspaceNameOf(UUID workspaceId) {
        return workspaces.findById(workspaceId).map(Workspace::getName);
    }

    @Override
    @Transactional
    public Enrolled enrolAsPlayer(UUID accountId, UUID workspaceId) {
        Workspace workspace = workspaces.findById(workspaceId)
                .orElseThrow(() -> ApiException.notFound("tournament"));

        Optional<Membership> existing =
                memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId);
        if (existing.isPresent()) {
            return enrolled(workspace, existing.get().getRole(), false);
        }

        memberships.save(new Membership(UuidV7.generate(), accountId, workspaceId, Role.PLAYER));
        audit.record(workspaceId, accountId, "member.joined", "account", accountId,
                Map.of("role", Role.PLAYER.name()));
        return enrolled(workspace, Role.PLAYER, true);
    }

    private static Enrolled enrolled(Workspace workspace, Role role, boolean joined) {
        return new Enrolled(workspace.getId(), workspace.getName(), workspace.getSlug(),
                role.name(), joined);
    }
}
