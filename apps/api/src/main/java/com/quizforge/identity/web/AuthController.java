package com.quizforge.identity.web;

import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.SessionService;
import com.quizforge.identity.Principal;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.identity.web.dto.AccountResponse;
import com.quizforge.identity.web.dto.LoginRequest;
import com.quizforge.identity.web.dto.RegisterRequest;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Explicitly named: the legacy cs.quizzapp AuthController would otherwise
// claim the same default bean name and the context would fail to start. The
// qualifier goes away with the legacy package in M3.
@RestController("identityAuthController")
@RequestMapping("/v1/auth")
public class AuthController {

    // GET /v1/auth/health lives in AuthHealthController.
    // Do not add it here as well - duplicate mappings fail context startup.

    private final AccountService accounts;
    private final SessionService sessions;

    public AuthController(AccountService accounts, SessionService sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@Valid @RequestBody RegisterRequest request) {
        return AccountResponse.of(
                accounts.register(request.email(), request.password(), request.displayName()));
    }

    @PostMapping("/login")
    public ResponseEntity<AccountResponse> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletRequest http) {
        var account = accounts.authenticate(request.email(), request.password());

        var issued = sessions.issue(account.getId(),
                http.getHeader("User-Agent"), http.getRemoteAddr());

        ResponseCookie cookie = ResponseCookie.from(SessionAuthFilter.COOKIE_NAME, issued.token())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(SessionService.LIFETIME)
                .build();

        return ResponseEntity.ok()
                .header("Set-Cookie", cookie.toString())
                .body(AccountResponse.of(account));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = SessionAuthFilter.COOKIE_NAME, required = false)
                       String token) {
        if (token != null) {
            sessions.revoke(token);
        }
    }

    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal Principal principal) {
        if (principal == null || principal.accountId() == null) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "authentication is required");
        }
        return AccountResponse.of(accounts.requireById(principal.accountId()));
    }

    /**
     * Always returns 202 with no detail, whether or not the address is
     * registered - otherwise this endpoint becomes an account-enumeration
     * oracle. The token is delivered by email and never in the response.
     */
    @PostMapping("/request-password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> requestPasswordReset(@RequestBody Map<String, String> body) {
        // Token generation and delivery are wired to the notify module in M4.
        return Map.of("message",
                "If that address has an account, password reset instructions have been sent.");
    }
}
