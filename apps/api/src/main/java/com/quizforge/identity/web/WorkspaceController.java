package com.quizforge.identity.web;

import com.quizforge.api.WorkspacesApi;
import com.quizforge.api.model.CreateWorkspaceRequest;
import com.quizforge.api.model.RenameWorkspaceRequest;
import com.quizforge.api.model.Role;
import com.quizforge.api.model.Workspace;
import com.quizforge.api.model.WorkspacePage;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.domain.Membership;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.web.PageWindow;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Workspaces — the tenant everything else belongs to.
 *
 * <p>Two of these operations are deliberately <em>account</em>-scoped rather
 * than workspace-scoped. Listing and creating workspaces cannot require one to
 * already be in scope: a newly registered account has none, and requiring one
 * would leave it permanently unable to use the API. {@code WorkspaceScopeFilter}
 * exempts {@code /v1/workspaces} by exact path for that reason, and nothing
 * below it.
 */
@RestController
public class WorkspaceController implements WorkspacesApi {

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public ResponseEntity<WorkspacePage> listWorkspaces(Integer limit, String cursor) {
        UUID accountId = requireAccount();
        PageWindow window = PageWindow.of(limit, cursor);

        // Paged over memberships rather than workspaces: the membership is what
        // scopes the query to this account, and it carries the role. The cursor
        // therefore names a membership, which is invisible to the client and
        // exactly why cursors are opaque.
        var slice = window.slice(workspaces.membershipsOf(accountId, window), Membership::getId);

        Map<UUID, Role> roles = slice.data().stream()
                .collect(Collectors.toMap(
                        Membership::getWorkspaceId,
                        m -> Role.fromValue(m.getRole().name())));

        // Resolved in the page's own order, not the database's: findAllById makes
        // no ordering promise, and a page whose order differs from its cursor is
        // a page that skips rows.
        Map<UUID, com.quizforge.identity.domain.Workspace> byId = workspaces
                .byIds(slice.data().stream().map(Membership::getWorkspaceId).toList())
                .stream()
                .collect(Collectors.toMap(com.quizforge.identity.domain.Workspace::getId, w -> w));

        List<Workspace> data = slice.data().stream()
                .map(m -> byId.get(m.getWorkspaceId()))
                .filter(java.util.Objects::nonNull)
                .map(w -> represent(w, roles.get(w.getId())))
                .toList();

        WorkspacePage page = new WorkspacePage(data);
        page.setNextCursor(slice.nextCursor());
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<Workspace> createWorkspace(CreateWorkspaceRequest request) {
        UUID accountId = requireAccount();
        var created = workspaces.create(accountId, request.getName());

        // The creator is the OWNER, so the role is known without a lookup.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(represent(created, Role.OWNER));
    }

    @Override
    public ResponseEntity<Workspace> getCurrentWorkspace(String workspaceHeader) {
        Principal principal = requireWorkspace();
        return ResponseEntity.ok(represent(
                workspaces.require(principal.workspaceId()), roleOf(principal)));
    }

    @Override
    public ResponseEntity<Workspace> renameWorkspace(RenameWorkspaceRequest request,
                                                     String workspaceHeader) {
        Principal principal = requireWorkspace();
        var renamed = workspaces.rename(principal.workspaceId(), principal.accountId(),
                request.getName());
        return ResponseEntity.ok(represent(renamed, roleOf(principal)));
    }

    private Workspace represent(com.quizforge.identity.domain.Workspace workspace, Role role) {
        return new Workspace(
                TypeId.render("wsp", workspace.getId()),
                workspace.getName(),
                workspace.getSlug(),
                role);
    }

    private Role roleOf(Principal principal) {
        return principal.role() == null ? null : Role.fromValue(principal.role().name());
    }

    /**
     * Listing and creating workspaces are things an <em>account</em> does. An
     * API key names one workspace already, so there is no set to enumerate and
     * nobody to attribute a new workspace to.
     */
    private UUID requireAccount() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.accountId() == null) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED,
                    "managing workspaces requires a signed-in account, not an API key");
        }
        return principal.accountId();
    }

    private Principal requireWorkspace() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }
        return principal;
    }
}
