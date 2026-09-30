package com.quizforge.identity.web;

import com.quizforge.api.AuthenticationApi;
import com.quizforge.api.model.Account;
import com.quizforge.api.model.HealthStatus;
import com.quizforge.api.model.LoginRequest;
import com.quizforge.api.model.PasswordResetRequest;
import com.quizforge.api.model.RegisterRequest;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.SessionService;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * Accounts, sessions and password-reset intake.
 *
 * <p>Implements {@link AuthenticationApi}, generated from {@code openapi.yaml}.
 * The paths, verbs, parameters and response types come from the contract, so a
 * change here that the contract does not describe fails to compile.
 *
 * <p>{@code GET /v1/auth/health} used to live in its own controller, so the
 * security configuration could be tested before any authentication endpoint
 * existed. That reason expired when they did, and the generated interface
 * carries all six operations of this tag, so it lives here now.
 */
@RestController
public class AuthController implements AuthenticationApi {

    private final AccountService accounts;
    private final SessionService sessions;
    private final HttpServletRequest http;
    private final com.quizforge.identity.SessionCookies cookies;

    public AuthController(AccountService accounts, SessionService sessions,
                          HttpServletRequest http, com.quizforge.identity.SessionCookies cookies) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.http = http;
        this.cookies = cookies;
    }

    @Override
    public ResponseEntity<HealthStatus> authHealth() {
        return ResponseEntity.ok(new HealthStatus(HealthStatus.StatusEnum.OK));
    }

    @Override
    public ResponseEntity<Account> register(RegisterRequest request) {
        var account = accounts.register(request.getEmail(), request.getPassword(),
                request.getDisplayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(represent(account));
    }

    @Override
    public ResponseEntity<Account> login(LoginRequest request) {
        var account = accounts.authenticate(request.getEmail(), request.getPassword());

        var issued = sessions.issue(account.getId(),
                http.getHeader("User-Agent"), http.getRemoteAddr());

        ResponseCookie cookie = cookies.issue(issued.token(), SessionService.LIFETIME);

        return ResponseEntity.ok()
                .header("Set-Cookie", cookie.toString())
                .body(represent(account));
    }

    /**
     * Takes no parameters, because the contract does not describe any. The
     * session cookie is a {@code securityScheme}, and the generator does not
     * turn credentials into method arguments - correctly, since a credential is
     * not part of an operation's signature.
     *
     * <p>So it is read from the request directly rather than with
     * {@code @CookieValue}. Succeeds either way: logging out with no cookie, or
     * with one naming a session that no longer exists, is still 204, so a
     * client can always retry.
     */
    @Override
    public ResponseEntity<Void> logout() {
        Cookie[] cookies = http.getCookies();
        if (cookies != null) {
            Arrays.stream(cookies)
                    .filter(c -> SessionAuthFilter.COOKIE_NAME.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .ifPresent(sessions::revoke);
        }
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Account> currentAccount() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.accountId() == null) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "authentication is required");
        }
        return ResponseEntity.ok(represent(accounts.requireById(principal.accountId())));
    }

    /**
     * Always 202 with an empty body, whether or not the address is registered -
     * otherwise this endpoint becomes an account-enumeration oracle. This build
     * does not generate or deliver reset tokens. Only the existence of the
     * account is concealed; a malformed request is still a 400, enforced by the
     * constraints the generator took from the contract.
     */
    @Override
    public ResponseEntity<Void> requestPasswordReset(PasswordResetRequest request) {
        return ResponseEntity.accepted().build();
    }

    /** Never carries the password hash, MFA secret, or lockout state. */
    private Account represent(com.quizforge.identity.domain.Account account) {
        Account body = new Account(
                TypeId.render("acc", account.getId()),
                account.getDisplayName(),
                account.getEmailVerifiedAt() != null);
        body.setEmail(account.getEmail());
        body.setGuest(account.isGuest());
        return body;
    }
}
