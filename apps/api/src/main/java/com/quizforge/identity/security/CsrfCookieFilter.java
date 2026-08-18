package com.quizforge.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces the CSRF token to be issued, so a client can read it.
 *
 * <p>Spring Security 6 loads the token lazily: the {@code XSRF-TOKEN} cookie is
 * only written when something actually reads the token, which happens on a
 * request that is CSRF-checked. Reads are not checked, so a client whose first
 * request is a {@code GET} — every browser client, on first load — receives no
 * cookie and has nothing to echo back on its first write.
 *
 * <p>Calling {@link CsrfToken#getToken()} resolves the deferred token, which
 * causes the repository to write the cookie. One line, and it is the difference
 * between the documented pattern working and not.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // The value is not used; resolving it is the point.
            token.getToken();
        }

        chain.doFilter(request, response);
    }
}
