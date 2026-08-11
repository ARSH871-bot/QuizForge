package com.quizforge.identity.app;

import com.quizforge.identity.domain.Session;
import com.quizforge.identity.repo.SessionRepository;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {

    public static final Duration LIFETIME = Duration.ofDays(14);

    /** The plaintext token is returned exactly once, at issue time. */
    public record IssuedSession(String token, Session session) {
    }

    private final SessionRepository sessions;

    public SessionService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional
    public IssuedSession issue(UUID accountId, String userAgent, String ipAddress) {
        String token = TokenDigest.generate();

        Session session = new Session(
                UuidV7.generate(), accountId, TokenDigest.digest(token),
                userAgent, ipAddress, Instant.now().plus(LIFETIME));

        return new IssuedSession(token, sessions.save(session));
    }

    @Transactional
    public Optional<Session> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        return sessions.findByTokenHash(TokenDigest.digest(token))
                .filter(Session::isActive)
                .map(session -> {
                    session.touch();
                    return sessions.save(session);
                });
    }

    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessions.findByTokenHash(TokenDigest.digest(token)).ifPresent(session -> {
            session.revoke();
            sessions.save(session);
        });
    }

    @Transactional
    public void revokeAllForAccount(UUID accountId) {
        sessions.findByAccountIdAndRevokedAtIsNull(accountId).forEach(session -> {
            session.revoke();
            sessions.save(session);
        });
    }
}
