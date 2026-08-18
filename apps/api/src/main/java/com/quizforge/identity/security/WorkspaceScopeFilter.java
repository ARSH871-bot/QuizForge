package com.quizforge.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Refuses an authenticated {@code /v1} request that has no workspace in scope.
 *
 * <p>This exists because tenant isolation is enforced by Row-Level Security,
 * and <strong>RLS only engages once a tenant is set</strong>.
 * {@code TenantAwareDataSource} applies {@code SET LOCAL ROLE quizforge_app}
 * only when {@link TenantContext} is populated, which happens only when the
 * workspace header (or an API key, which carries its own workspace) is present.
 * With no tenant, the connection runs as the owning role and PostgreSQL skips
 * RLS for superusers entirely — so every tenant predicate silently evaporates.
 *
 * <p>That was not theoretical. Two endpoints were confirmed to leak across
 * workspaces when the header was simply omitted: standings returned another
 * workspace's rows, and starting an attempt on another workspace's tournament
 * returned {@code 201}. Both were reachable by any authenticated account.
 *
 * <p>The fix is here rather than in each controller deliberately. Per-endpoint
 * checks are correct exactly until someone adds the twenty-sixth endpoint and
 * forgets one, and the failure is invisible — the endpoint works, it just works
 * for everybody. A request that cannot be tenant-scoped does not reach a
 * handler at all.
 *
 * <p>Paths that are legitimately account-scoped rather than workspace-scoped
 * are listed in {@link #WORKSPACE_FREE}. That list is deliberately explicit:
 * adding to it should require saying why.
 */
@Component
public class WorkspaceScopeFilter extends OncePerRequestFilter {

    /**
     * Prefixes that operate on the account rather than a workspace.
     *
     * <p>{@code /v1/auth/**} covers registration, login, logout, {@code /me}
     * and password reset — none of which can have a workspace, because some of
     * them run before the account has one.
     */
    private static final List<String> WORKSPACE_FREE = List.of("/v1/auth/");

    private final ObjectMapper objectMapper;

    public WorkspaceScopeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (requiresWorkspace(request) && TenantContext.current() == null) {
            reject(request, response);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean requiresWorkspace(HttpServletRequest request) {
        String path = request.getRequestURI();

        if (path == null || !path.startsWith("/v1/")) {
            return false;
        }
        if (WORKSPACE_FREE.stream().anyMatch(path::startsWith)) {
            return false;
        }
        // An unauthenticated request is refused by the entry point with a 401,
        // which is the more useful answer. Do not pre-empt it with a 400.
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCode.INVALID_REQUEST.status(),
                "select a workspace with the " + SessionAuthFilter.WORKSPACE_HEADER + " header");
        problem.setType(URI.create(ErrorCode.INVALID_REQUEST.type()));
        problem.setTitle(ErrorCode.INVALID_REQUEST.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", ErrorCode.INVALID_REQUEST.name());

        response.setStatus(ErrorCode.INVALID_REQUEST.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
