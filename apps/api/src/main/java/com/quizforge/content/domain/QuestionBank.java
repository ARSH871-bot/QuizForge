package com.quizforge.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "question_bank")
public class QuestionBank {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    private long version;

    protected QuestionBank() {
    }

    public QuestionBank(UUID id, UUID workspaceId, String name, UUID createdBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.name = name;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getArchivedAt() { return archivedAt; }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public void describe(String description) {
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.archivedAt = Instant.now();
        this.updatedAt = this.archivedAt;
    }
}
