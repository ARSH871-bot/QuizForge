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
     * Path prefixes that operate on the account rather than a workspace.
     *
     * <p>{@code /v1/auth/**} covers registration, login, logout, {@code /me}
     * and password reset — none of which can have a workspace, because some of
     * them run before the account has one.
     */
    private static final List<String> WORKSPACE_FREE_PREFIXES = List.of("/v1/auth/", "/v1/join/");

    /**
     * Exact paths that operate on the account rather than a workspace.
     *
     * <p>Matched exactly, not as a prefix, and the distinction is the whole
     * point. {@code /v1/workspaces} lists and creates workspaces, which a new
     * account must be able to do before it has one. Everything <em>below</em>
     * that path — {@code /v1/workspaces/current} — acts on a specific workspace
     * and must still be scoped.
     *
     * <p>A prefix match here would exempt the sub-paths too, which is exactly
     * the cross-tenant hole this filter exists to close.
     */
    private static final List<String> WORKSPACE_FREE_EXACT = List.of("/v1/workspaces");

    private final ObjectMapper objectMapper;

    public WorkspaceScopeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (requiresWorkspace(request) && TenantContext.current() == null) {
            boolean denied = Boolean.TRUE.equals(request.getAttribute(
                    SessionAuthFilter.WORKSPACE_DENIED));

            if (denied) {
                // A workspace was named and refused, which is an authorization
                // failure, not a missing-parameter one.
                reject(request, response, ErrorCode.PERMISSION_DENIED,
                        "you are not a member of that workspace");
            } else {
                reject(request, response, ErrorCode.INVALID_REQUEST,
                        "select a workspace with the "
                                + SessionAuthFilter.WORKSPACE_HEADER + " header");
            }
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean requiresWorkspace(HttpServletRequest request) {
        String path = request.getRequestURI();

        if (path == null || !path.startsWith("/v1/")) {
            return false;
        }
        if (WORKSPACE_FREE_PREFIXES.stream().anyMatch(path::startsWith)) {
            return false;
        }
        if (WORKSPACE_FREE_EXACT.contains(stripTrailingSlash(path))) {
            return false;
        }
        // An unauthenticated request is refused by the entry point with a 401,
        // which is the more useful answer. Do not pre-empt it with a 400.
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    /** So that {@code /v1/workspaces/} is treated as {@code /v1/workspaces}. */
    private static String stripTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        ErrorCode code, String detail) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
