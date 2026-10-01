package com.quizforge.identity.app;

import com.quizforge.identity.domain.Account;
import com.quizforge.identity.domain.PasswordResetToken;
import com.quizforge.identity.repo.AccountRepository;
import com.quizforge.identity.repo.PasswordResetTokenRepository;
import com.quizforge.notify.Mailer;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.id.UuidV7;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Resetting a forgotten password by proving you can read the account's email.
 *
 * <p>The link is the credential, so it is treated like one: 256 bits from a
 * CSPRNG, stored only as a digest, good for an hour and for one use, and
 * superseded by any newer link. Completing a reset signs the account out
 * everywhere, because a reset is often a response to someone else getting in.
 */
@Service
public class PasswordResetService {

    static final Duration LIFETIME = Duration.ofHours(1);

    /** One email per account per interval, so the form cannot flood an inbox. */
    static final Duration COOLDOWN = Duration.ofMinutes(2);

    private final AccountRepository accounts;
    private final PasswordResetTokenRepository tokens;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final Mailer mailer;
    private final String appBaseUrl;

    public PasswordResetService(AccountRepository accounts, PasswordResetTokenRepository tokens,
                                SessionService sessions, PasswordEncoder passwordEncoder, Mailer mailer,
                                @Value("${quizforge.app.base-url:http://localhost:8080}") String appBaseUrl) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Sends a reset link if the address has an account. Says nothing either way:
     * the caller learns the same thing whether or not the address exists.
     */
    @Transactional
    public void request(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        accounts.findByEmailIgnoreCase(email.trim())
                .filter(account -> !account.isGuest())
                .ifPresent(this::issue);
    }

    private void issue(Account account) {
        Instant now = Instant.now();
        boolean recentlySent = tokens.findFirstByAccountIdOrderByCreatedAtDesc(account.getId())
                .map(t -> t.getCreatedAt().isAfter(now.minus(COOLDOWN)))
                .orElse(false);
        if (recentlySent) {
            return;
        }

        tokens.spendAll(account.getId(), now);
        String token = TokenDigest.generate();
        tokens.save(new PasswordResetToken(UuidV7.generate(), account.getId(),
                TokenDigest.digest(token), now.plus(LIFETIME)));

        // Joined with explicit line breaks rather than formatted: a format
        // string's newlines are platform-dependent, and mail wants them fixed.
        String body = String.join("\n",
                "Hi " + account.getDisplayName() + ",",
                "",
                "Someone asked to reset the password for this QuizForge account. If it",
                "was you, choose a new one here within the next hour:",
                "",
                appBaseUrl + "/reset-password?token=" + token,
                "",
                "If it wasn't you, ignore this email; your password stays as it is.");
        mailer.send(new Mailer.OutgoingMail(
                account.getEmail(), "Reset your QuizForge password", body));
    }

    /** Sets a new password with a valid link, and signs the account out everywhere. */
    @Transactional
    public void reset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < AccountService.MIN_PASSWORD_LENGTH) {
            throw ApiException.invalid(
                    "password must be at least " + AccountService.MIN_PASSWORD_LENGTH + " characters");
        }
        Instant now = Instant.now();
        PasswordResetToken reset = token == null ? null
                : tokens.findByTokenHash(TokenDigest.digest(token)).orElse(null);
        if (reset == null || !reset.isUsableAt(now)) {
            throw ApiException.invalid("this reset link is invalid or has expired; ask for a new one");
        }

        Account account = accounts.findById(reset.getAccountId())
                .orElseThrow(() -> ApiException.invalid("this reset link is invalid or has expired; ask for a new one"));
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        // Proving control of the email is a stronger signal than any password
        // guess, so it also lifts a lockout from earlier failed sign-ins.
        account.recordSuccessfulLogin();
        accounts.save(account);
        reset.use(now);
        tokens.save(reset);
        sessions.revokeAllForAccount(account.getId());
    }
}
