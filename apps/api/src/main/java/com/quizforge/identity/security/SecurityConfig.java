package com.quizforge.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.platform.idempotency.IdempotencyFilter;
import com.quizforge.platform.ratelimit.RateLimitFilter;
import com.quizforge.platform.error.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.net.URI;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Accepts the token exactly as the {@code XSRF-TOKEN} cookie carries it.
     *
     * <p>Setting the request attribute name to {@code null} is what disables
     * the XOR masking; the handler otherwise behaves as the default does.
     */
    static CsrfTokenRequestAttributeHandler plainCsrfTokenHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SessionAuthFilter sessionAuth,
                                           ApiKeyAuthFilter apiKeyAuth,
                                           WorkspaceScopeFilter workspaceScope,
                                           IdempotencyFilter idempotency,
                                           RateLimitFilter rateLimit) throws Exception {
        http
            // Stateless: authentication comes from an opaque cookie or bearer
            // token resolved against the database, never from an HTTP session.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // CSRF protection is enabled for cookie-authenticated requests,
            // which are the only ones a cross-site form can forge. Two classes
            // of request are exempt, deliberately:
            //
            //   - Bearer-token requests. A custom Authorization header cannot
            //     be set cross-origin without a CORS preflight the browser
            //     will refuse, so there is nothing to forge.
            //   - /v1/auth/**. These run before a session exists, so there is
            //     no token to present and no authenticated state to protect.
            //     Login CSRF and forced logout remain possible; both are
            //     nuisances rather than breaches, and the alternative is a
            //     bootstrap round-trip on every unauthenticated call.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // Opts out of Spring Security 6's XOR token masking.
                //
                // The masking exists to defeat BREACH, which reads a secret out
                // of a compressed *response body*. This application never puts
                // the token in a body - it exists only in the XSRF-TOKEN cookie
                // the client reads and echoes back - so the protection guards
                // nothing here.
                //
                // What it did do was break the documented pattern entirely. With
                // masking on, the cookie holds the raw token while the header
                // must carry a masked one, so a client that echoes the cookie
                // back is rejected. No cookie-authenticated write was possible
                // for any real client, and no test could see it: MockMvc's
                // .with(csrf()) constructs a valid masked token internally, so
                // the suite exercised a path no browser or curl can take.
                .csrfTokenRequestHandler(plainCsrfTokenHandler())
                .ignoringRequestMatchers(
                        new AntPathRequestMatcher("/v1/auth/**"),
                        request -> {
                            String header = request.getHeader("Authorization");
                            return header != null && header.startsWith("Bearer qf_");
                        }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                // Serialised by Jackson rather than composed as a string.
                // FindSecBugs 1.14 flags the hand-written version as
                // XSS_SERVLET. That is a false positive here - the only
                // interpolated value is a compile-time constant - but writing
                // JSON by hand into a servlet response is the smell the
                // detector points at, and it is not worth defending.
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                        ErrorCode.AUTHENTICATION_REQUIRED.status(),
                        "authentication is required");
                problem.setType(URI.create(ErrorCode.AUTHENTICATION_REQUIRED.type()));
                problem.setTitle(ErrorCode.AUTHENTICATION_REQUIRED.name());
                problem.setProperty("code", ErrorCode.AUTHENTICATION_REQUIRED.name());

                response.setStatus(ErrorCode.AUTHENTICATION_REQUIRED.status().value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), problem);
            }))
            // After the CSRF filter, so the token exists to be resolved.
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .addFilterBefore(apiKeyAuth, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(sessionAuth, UsernamePasswordAuthenticationFilter.class)
            // After both authentication filters: it inspects the tenant they
            // established, so it cannot run before them.
            .addFilterAfter(workspaceScope, UsernamePasswordAuthenticationFilter.class)
            // Last, and after the workspace check: it scopes records to the
            // tenant, so it must not run before there is one.
            // Before idempotency: a refused request should not consume a key,
            // and a caller over its limit should be told so rather than having
            // its retry silently recorded.
            .addFilterAfter(rateLimit, WorkspaceScopeFilter.class)
            .addFilterAfter(idempotency, RateLimitFilter.class);

        return http.build();
    }
}
