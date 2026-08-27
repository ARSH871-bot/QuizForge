package com.quizforge.platform.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Spends a token per request and reports the budget.
 *
 * <h2>Headers on every response, not only on 429</h2>
 *
 * <p>A client told its budget only once it has run out has been told too late.
 * Sending {@code RateLimit-*} on every response lets a well-behaved client slow
 * down before it is refused, which is the difference between rate limiting as a
 * signal and rate limiting as a punishment.
 *
 * <p>The header names follow
 * <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">draft-ietf-httpapi-ratelimit-headers</a>.
 * The platform design cited RFC 9331 for these; that RFC is the L4S congestion
 * notification protocol and has nothing to do with HTTP rate limits. The
 * citation was corrected rather than followed.
 *
 * <h2>What is not limited</h2>
 *
 * <p>Requests with no workspace in scope — {@code /v1/auth/**} and workspace
 * creation. The bucket is tenant-scoped, and those requests have no tenant to
 * scope it to. Login is the one that would otherwise matter, and it has its own
 * control: repeated failures lock the account, which is a better answer to
 * password guessing than a shared counter.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String LIMIT_HEADER = "RateLimit-Limit";
    public static final String REMAINING_HEADER = "RateLimit-Remaining";
    public static final String RESET_HEADER = "RateLimit-Reset";
    public static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RateLimiter limiter;
    private final ObjectMapper objectMapper;
    private final RateLimitSubject subject;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper objectMapper,
                           RateLimitSubject subject) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
        this.subject = subject;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        UUID workspaceId = TenantContext.current();
        UUID subjectId = subject.current();

        if (workspaceId == null || subjectId == null || !request.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }

        RateLimiter.Decision decision = limiter.consume(subjectId, workspaceId);

        response.setHeader(LIMIT_HEADER, String.valueOf(decision.limit()));
        response.setHeader(REMAINING_HEADER, String.valueOf(Math.max(0, decision.remaining())));
        response.setHeader(RESET_HEADER, String.valueOf(decision.resetSeconds()));

        if (!decision.allowed()) {
            // Retry-After as well: RateLimit-Reset is the newer, more precise
            // field, but Retry-After is the one every HTTP client already
            // understands, and a 429 nobody can act on is just a rejection.
            response.setHeader(RETRY_AFTER_HEADER,
                    String.valueOf(Math.max(1, decision.resetSeconds())));
            refuse(request, response);
            return;
        }

        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCode.RATE_LIMITED.status(),
                "too many requests; slow down and retry after the interval in Retry-After");
        problem.setType(URI.create(ErrorCode.RATE_LIMITED.type()));
        problem.setTitle(ErrorCode.RATE_LIMITED.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", ErrorCode.RATE_LIMITED.name());

        response.setStatus(ErrorCode.RATE_LIMITED.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
