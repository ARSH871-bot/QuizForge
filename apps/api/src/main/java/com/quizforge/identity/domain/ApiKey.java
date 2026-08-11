package com.quizforge.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "last_four", nullable = false, length = 4)
    private String lastFour;

    @Column(nullable = false, length = 8)
    private String environment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
    }

    public ApiKey(UUID id, UUID workspaceId, UUID createdBy, String name,
                  String tokenHash, String lastFour, String environment) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.createdBy = createdBy;
        this.name = name;
        this.tokenHash = tokenHash;
        this.lastFour = lastFour;
        this.environment = environment;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getTokenHash() { return tokenHash; }
    public String getLastFour() { return lastFour; }
    public String getEnvironment() { return environment; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public void markUsed() {
        this.lastUsedAt = Instant.now();
    }
}
