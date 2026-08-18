package com.quizforge.identity.security;

import com.quizforge.identity.Principal;
import com.quizforge.identity.app.SessionService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "qf_session";
    public static final String WORKSPACE_HEADER = "X-QuizForge-Workspace";

    /**
     * Set when a valid session named a workspace the account is not a member
     * of. {@code WorkspaceScopeFilter} turns it into a {@code 403}.
     *
     * <p>A request attribute rather than an exception because this filter runs
     * before the dispatcher, so a thrown {@link com.quizforge.platform.error.ApiException}
     * would never reach {@code GlobalExceptionHandler}.
     */
    public static final String WORKSPACE_DENIED =
            SessionAuthFilter.class.getName() + ".WORKSPACE_DENIED";

    private final SessionService sessions;
    private final WorkspaceService workspaces;

    public SessionAuthFilter(SessionService sessions, WorkspaceService workspaces) {
        this.sessions = sessions;
        this.workspaces = workspaces;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // Both are ThreadLocal-backed and the thread is returned to a pool.
            // Clearing here rather than in each filter guarantees it happens
            // exactly once, on the way out of the outermost filter.
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(HttpServletRequest request) {
        Optional<String> token = cookie(request);
        if (token.isEmpty()) {
            return;
        }

        sessions.resolve(token.get()).ifPresent(session -> {
            UUID workspaceId = requestedWorkspace(request);
            Role role = workspaceId == null
                    ? null
                    : workspaces.roleOf(workspaceId, session.getAccountId());

            if (workspaceId != null && role == null) {
                // The session is valid; the workspace is not the caller's.
                // Authenticate them anyway, without the workspace, and flag the
                // refusal so it surfaces as 403 rather than 401.
                //
                // Returning early here used to leave the principal unset, so a
                // caller holding a perfectly good session was told they were not
                // authenticated. They would then re-authenticate, get the same
                // 401, and have nothing pointing at the header that was actually
                // wrong.
                request.setAttribute(WORKSPACE_DENIED, Boolean.TRUE);
                workspaceId = null;
            }

            Principal principal = new Principal(
                    session.getAccountId(), workspaceId, role, Principal.AuthType.SESSION);

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, List.of()));

            if (workspaceId != null) {
                TenantContext.set(workspaceId);
            }
        });
    }

    private Optional<String> cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private UUID requestedWorkspace(HttpServletRequest request) {
        String header = request.getHeader(WORKSPACE_HEADER);
        if (header == null || header.isBlank()) {
            return null;
        }
        return TypeId.parse("wsp", header);
    }
}
