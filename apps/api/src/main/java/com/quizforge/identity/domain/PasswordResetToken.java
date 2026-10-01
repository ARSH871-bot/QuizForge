package com.quizforge.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A single-use, short-lived proof that someone can read an account's email. */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected PasswordResetToken() {
    }

    public PasswordResetToken(UUID id, UUID accountId, String tokenHash, Instant expiresAt) {
        this.id = id;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getAccountId() { return accountId; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isUsableAt(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void use(Instant now) {
        this.usedAt = now;
    }
}
