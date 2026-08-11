package com.quizforge.identity.app;

import com.quizforge.identity.domain.Account;
import com.quizforge.identity.repo.AccountRepository;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private static final int MIN_PASSWORD_LENGTH = 12;

    /**
     * A precomputed hash of a value nobody will ever submit. Verified when the
     * account does not exist, so that a request for an unknown email costs the
     * same as one for a known email. Without this, response timing reveals
     * which addresses are registered.
     */
    private final String dummyHash;

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Account register(String email, String password, String displayName) {
        validatePassword(password);

        if (email == null || !email.contains("@")) {
            throw ApiException.invalid("a valid email address is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw ApiException.invalid("display name is required");
        }

        accounts.findByEmailIgnoreCase(email).ifPresent(existing -> {
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "an account with that email already exists");
        });

        Account account = new Account(
                UuidV7.generate(), email, passwordEncoder.encode(password), displayName);

        return accounts.save(account);
    }

    @Transactional
    public Account authenticate(String email, String password) {
        var found = accounts.findByEmailIgnoreCase(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);   // equalise timing
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "invalid email or password");
        }

        Account account = found.get();

        if (account.isLocked()) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "invalid email or password");
        }

        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            account.recordFailedLogin();
            accounts.save(account);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "invalid email or password");
        }

        account.recordSuccessfulLogin();
        return accounts.save(account);
    }

    @Transactional
    public void changePassword(UUID accountId, String current, String replacement) {
        validatePassword(replacement);

        Account account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account"));

        if (!passwordEncoder.matches(current, account.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "current password is incorrect");
        }

        account.setPasswordHash(passwordEncoder.encode(replacement));
        accounts.save(account);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.invalid(
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
