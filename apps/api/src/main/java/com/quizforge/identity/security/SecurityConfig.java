package com.quizforge.identity.security;

import com.quizforge.platform.error.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SessionAuthFilter sessionAuth,
                                           ApiKeyAuthFilter apiKeyAuth) throws Exception {
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
                .ignoringRequestMatchers(
                        new AntPathRequestMatcher("/v1/auth/**"),
                        new AntPathRequestMatcher("/api/**"),
                        request -> {
                            String header = request.getHeader("Authorization");
                            return header != null && header.startsWith("Bearer qf_");
                        }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // The legacy quiz API remains open until M3 replaces it.
                .requestMatchers("/api/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                response.setStatus(ErrorCode.AUTHENTICATION_REQUIRED.status().value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getWriter().write("""
                    {"type":"%s","title":"AUTHENTICATION_REQUIRED",\
                    "status":401,"detail":"authentication is required",\
                    "code":"AUTHENTICATION_REQUIRED"}"""
                        .formatted(ErrorCode.AUTHENTICATION_REQUIRED.type()));
            }))
            .addFilterBefore(apiKeyAuth, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(sessionAuth, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
