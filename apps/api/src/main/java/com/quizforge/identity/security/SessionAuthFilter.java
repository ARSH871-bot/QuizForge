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
                return;   // membership is required to act in a workspace
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
