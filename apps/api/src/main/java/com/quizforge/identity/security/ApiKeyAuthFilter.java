package com.quizforge.identity.security;

import com.quizforge.identity.Principal;
import com.quizforge.identity.app.ApiKeyService;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer qf_";

    private final ApiKeyService apiKeys;

    public ApiKeyAuthFilter(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith(PREFIX)) {
            String secret = header.substring("Bearer ".length());

            apiKeys.resolve(secret).ifPresent(key -> {
                // An API key grants ADMIN within its workspace. Finer-grained
                // scopes arrive in M4 with the public API contract.
                Principal principal = new Principal(
                        null, key.getWorkspaceId(), Role.ADMIN, Principal.AuthType.API_KEY,
                        key.getId());

                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                TenantContext.set(key.getWorkspaceId());
            });
        }

        chain.doFilter(request, response);
    }
}
