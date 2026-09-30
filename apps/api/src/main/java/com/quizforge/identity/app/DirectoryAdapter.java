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
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.error.RateLimitedException;
import com.quizforge.platform.id.UuidV7;
import com.quizforge.platform.ratelimit.RateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Collectors;

/** Implements identity's published directory and enrolment APIs. */
@Service
public class DirectoryAdapter implements AccountDirectory, WorkspaceEnrolment {

    private final AccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final AuditService audit;
    private final SessionService sessions;
    private final RateLimiter limiter;

    /**
     * New guest identities per network address, per workspace, per minute.
     *
     * <p>High enough for a class of thirty behind one school address to join at
     * once; low enough that minting identities to dodge an attempt limit is
     * slow and visible on the leaderboard.
     */
    static final int GUEST_JOINS_PER_MINUTE = 30;

    public DirectoryAdapter(AccountRepository accounts, WorkspaceRepository workspaces,
                            MembershipRepository memberships, AuditService audit,
                            SessionService sessions, RateLimiter limiter) {
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.audit = audit;
        this.sessions = sessions;
        this.limiter = limiter;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Person> peopleOf(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return accounts.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId,
                        a -> new Person(a.getDisplayName(), a.isGuest())));
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

    @Override
    @Transactional
    public GuestEnrolled enrolAsGuest(UUID workspaceId, String nickname, String clientAddress,
                                      String userAgent) {
        String name = nickname == null ? "" : nickname.strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 40 || name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.invalid("a nickname needs 1 to 40 visible characters");
        }
        if (workspaces.findById(workspaceId).isEmpty()) {
            throw ApiException.notFound("tournament");
        }

        // Checked before the name, so probing which names exist costs budget too.
        UUID subject = UUID.nameUUIDFromBytes(
                ("guest-join:" + clientAddress).getBytes(StandardCharsets.UTF_8));
        var decision = limiter.consume(subject, workspaceId, GUEST_JOINS_PER_MINUTE);
        if (!decision.allowed()) {
            throw new RateLimitedException(
                    "too many new players from this network; try again shortly",
                    decision.resetSeconds());
        }

        if (memberships.nameTakenInWorkspace(workspaceId, name)) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "someone here already uses the name '" + name + "'; pick another");
        }

        Account guest = accounts.save(Account.guest(UuidV7.generate(), name));
        memberships.save(new Membership(UuidV7.generate(), guest.getId(), workspaceId, Role.PLAYER));
        audit.record(workspaceId, guest.getId(), "member.joined", "account", guest.getId(),
                Map.of("role", Role.PLAYER.name(), "guest", true));

        var issued = sessions.issue(guest.getId(), userAgent, clientAddress);
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        return new GuestEnrolled(enrolled(workspace, Role.PLAYER, true), issued.token(),
                SessionService.LIFETIME);
    }
}
