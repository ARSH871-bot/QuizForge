package com.quizforge.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id;

    @Column(length = 320)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private boolean guest;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Column(name = "mfa_enabled_at")
    private Instant mfaEnabledAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected Account() {
    }

    public Account(UUID id, String email, String passwordHash, String displayName) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    /**
     * A guest: a name and nothing else. No email and no password, so it can
     * never be signed into again - it lives only in the session it is issued.
     */
    public static Account guest(UUID id, String displayName) {
        Account account = new Account(id, null, null, displayName);
        account.guest = true;
        return account;
    }

    public boolean isGuest() { return guest; }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public String getMfaSecret() { return mfaSecret; }
    public Instant getMfaEnabledAt() { return mfaEnabledAt; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public void recordFailedLogin() {
        this.failedLoginCount++;
        if (this.failedLoginCount >= 10) {
            this.lockedUntil = Instant.now().plusSeconds(900);
            this.failedLoginCount = 0;
        }
    }

    public void recordSuccessfulLogin() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }
}
