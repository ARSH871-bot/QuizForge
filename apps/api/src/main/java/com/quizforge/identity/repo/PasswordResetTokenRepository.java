package com.quizforge.identity.repo;

import com.quizforge.identity.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    Optional<PasswordResetToken> findFirstByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /** Spends every outstanding token for an account, so only the newest link works. */
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.accountId = :accountId AND t.usedAt IS NULL")
    int spendAll(@Param("accountId") UUID accountId, @Param("now") Instant now);
}
