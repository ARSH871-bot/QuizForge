package com.quizforge.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "membership")
public class Membership {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Membership() {
    }

    public Membership(UUID id, UUID accountId, UUID workspaceId, Role role) {
        this.id = id;
        this.accountId = accountId;
        this.workspaceId = workspaceId;
        this.role = role;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public Role getRole() { return role; }

    public void changeRole(Role role) {
        this.role = role;
    }
}
