package com.quizforge.identity.web.dto;

import com.quizforge.identity.domain.Account;
import com.quizforge.platform.id.TypeId;

/** Never carries the password hash, MFA secret, or lockout state. */
public record AccountResponse(String id, String email, String displayName, boolean emailVerified) {

    public static AccountResponse of(Account account) {
        return new AccountResponse(
                TypeId.render("acc", account.getId()),
                account.getEmail(),
                account.getDisplayName(),
                account.getEmailVerifiedAt() != null);
    }
}
