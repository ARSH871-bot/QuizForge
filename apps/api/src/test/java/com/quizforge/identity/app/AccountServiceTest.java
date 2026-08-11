package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountServiceTest extends AbstractIntegrationTest {

    @Autowired
    private AccountService accounts;

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void registersAnAccountAndHashesThePassword() {
        String email = uniqueEmail();

        var account = accounts.register(email, "correct horse battery", "Ada");

        assertThat(account.getId()).isNotNull();
        assertThat(account.getEmail()).isEqualTo(email);
        assertThat(account.getPasswordHash())
                .doesNotContain("correct horse battery")
                .startsWith("{argon2}");
    }

    @Test
    void rejectsDuplicateEmailRegardlessOfCase() {
        String email = uniqueEmail();
        accounts.register(email, "correct horse battery", "Ada");

        assertThatThrownBy(() ->
                accounts.register(email.toUpperCase(), "another password", "Imposter"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.ALREADY_EXISTS));
    }

    @Test
    void authenticatesWithTheCorrectPassword() {
        String email = uniqueEmail();
        accounts.register(email, "correct horse battery", "Ada");

        var authenticated = accounts.authenticate(email, "correct horse battery");

        assertThat(authenticated.getEmail()).isEqualTo(email);
    }

    @Test
    void rejectsAWrongPasswordWithTheSameErrorAsAnUnknownAccount() {
        String email = uniqueEmail();
        accounts.register(email, "correct horse battery", "Ada");

        assertThatThrownBy(() -> accounts.authenticate(email, "wrong password here"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        assertThatThrownBy(() -> accounts.authenticate(uniqueEmail(), "wrong password here"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void rejectsShortPasswords() {
        assertThatThrownBy(() -> accounts.register(uniqueEmail(), "short", "Ada"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least 12");
    }
}
