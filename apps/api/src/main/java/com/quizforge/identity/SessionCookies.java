package com.quizforge.identity;

import com.quizforge.identity.security.SessionAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The session cookie, built one way wherever a session is issued.
 *
 * <p>Signing in and joining as a guest both start a session. Building the
 * cookie in two places is how one of them ends up without {@code HttpOnly}, or
 * ignoring the {@code Secure} setting.
 */
@Component
public class SessionCookies {

    private final boolean secure;

    public SessionCookies(@Value("${quizforge.security.session-cookie-secure:true}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie issue(String token, Duration lifetime) {
        return ResponseCookie.from(SessionAuthFilter.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(lifetime)
                .build();
    }
}
