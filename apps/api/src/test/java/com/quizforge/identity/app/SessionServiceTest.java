package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTest extends AbstractIntegrationTest {

    @Autowired private SessionService sessions;
    @Autowired private AccountService accounts;

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void issuesAResolvableTokenThatIsNotStoredInPlaintext() {
        UUID account = newAccount();

        var issued = sessions.issue(account, "test-agent", "127.0.0.1");

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.session().getTokenHash()).isNotEqualTo(issued.token());
        assertThat(sessions.resolve(issued.token()))
                .isPresent()
                .get()
                .satisfies(s -> assertThat(s.getAccountId()).isEqualTo(account));
    }

    @Test
    void doesNotResolveAnUnknownToken() {
        assertThat(sessions.resolve("totally-made-up-token")).isEmpty();
    }

    @Test
    void doesNotResolveARevokedToken() {
        var issued = sessions.issue(newAccount(), "test-agent", "127.0.0.1");

        sessions.revoke(issued.token());

        assertThat(sessions.resolve(issued.token())).isEmpty();
    }

    @Test
    void issuesADistinctTokenEveryTime() {
        UUID account = newAccount();

        var first = sessions.issue(account, "a", "127.0.0.1");
        var second = sessions.issue(account, "b", "127.0.0.1");

        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void revokingAllSessionsInvalidatesEveryOne() {
        UUID account = newAccount();
        var first = sessions.issue(account, "a", "127.0.0.1");
        var second = sessions.issue(account, "b", "127.0.0.1");

        sessions.revokeAllForAccount(account);

        assertThat(sessions.resolve(first.token())).isEmpty();
        assertThat(sessions.resolve(second.token())).isEmpty();
    }
}
