package com.quizforge.identity.web;

import com.quizforge.api.MembersApi;
import com.quizforge.api.model.AddMemberRequest;
import com.quizforge.api.model.ChangeRoleRequest;
import com.quizforge.api.model.Member;
import com.quizforge.api.model.MemberPage;
import com.quizforge.api.model.Role;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.domain.Account;
import com.quizforge.identity.domain.Membership;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Who belongs to a workspace, and what they may do.
 *
 * <p>These paths carry no workspace id. The workspace is the one already in
 * scope, selected by header or implied by an API key. Repeating it in the path
 * would create a second way to name a tenant, and reconciling two answers when
 * they disagree is how cross-tenant defects get written.
 */
@RestController
public class MemberController implements MembersApi {

    private final WorkspaceService workspaces;
    private final AccountService accounts;

    public MemberController(WorkspaceService workspaces, AccountService accounts) {
        this.workspaces = workspaces;
        this.accounts = accounts;
    }

    @Override
    public ResponseEntity<MemberPage> listMembers(String workspaceHeader) {
        UUID workspaceId = requireWorkspace().workspaceId();

        List<Member> data = workspaces.membersOf(workspaceId).stream()
                .map(m -> represent(m, accounts.requireById(m.getAccountId())))
                .sorted(Comparator.comparing(Member::getEmail))
                .toList();

        MemberPage page = new MemberPage(data);
        page.setNextCursor(null);
        return ResponseEntity.ok(page);
    }

    /**
     * The account must already exist; there is no invitation flow yet, so an
     * unregistered address is a 404. That does reveal to a workspace
     * administrator whether an address has an account — documented in the
     * contract rather than concealed, because reporting success for a member who
     * was not added would be a lie the caller acts on.
     */
    @Override
    public ResponseEntity<Member> addMember(AddMemberRequest request, String workspaceHeader) {
        Principal principal = requireWorkspace();

        Account account = accounts.requireByEmail(request.getEmail());
        Membership membership = workspaces.addMember(principal.workspaceId(),
                principal.accountId(), account.getId(), domainRole(request.getRole()));

        return ResponseEntity.status(HttpStatus.CREATED).body(represent(membership, account));
    }

    @Override
    public ResponseEntity<Member> changeMemberRole(String accountId, ChangeRoleRequest request,
                                                   String workspaceHeader) {
        Principal principal = requireWorkspace();
        UUID target = TypeId.parse("acc", accountId);

        workspaces.changeRole(principal.workspaceId(), principal.accountId(), target,
                domainRole(request.getRole()));

        Membership membership = workspaces.membersOf(principal.workspaceId()).stream()
                .filter(m -> m.getAccountId().equals(target))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("membership"));

        return ResponseEntity.ok(represent(membership, accounts.requireById(target)));
    }

    @Override
    public ResponseEntity<Void> removeMember(String accountId, String workspaceHeader) {
        Principal principal = requireWorkspace();
        workspaces.removeMember(principal.workspaceId(), principal.accountId(),
                TypeId.parse("acc", accountId));
        return ResponseEntity.noContent().build();
    }

    private Member represent(Membership membership, Account account) {
        return new Member(
                TypeId.render("acc", account.getId()),
                account.getEmail(),
                account.getDisplayName(),
                Role.fromValue(membership.getRole().name()));
    }

    private com.quizforge.identity.domain.Role domainRole(Role role) {
        if (role == null) {
            throw ApiException.invalid("role is required");
        }
        return com.quizforge.identity.domain.Role.valueOf(role.getValue());
    }

    private Principal requireWorkspace() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }
        if (principal.accountId() == null) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED,
                    "managing members requires a signed-in account, not an API key");
        }
        return principal;
    }
}
