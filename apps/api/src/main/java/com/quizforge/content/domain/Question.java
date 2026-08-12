package com.quizforge.content.domain;

import com.quizforge.content.domain.payload.Payload;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable question. Editing never updates a row: a new row is inserted
 * sharing {@code lineageId} with {@code version + 1}, and the previous row is
 * stamped with {@code supersededBy}.
 *
 * <p>That is what lets a tournament pin exactly what a player saw by holding a
 * plain {@code question.id}, with no risk that a later edit rewrites history.
 * The only mutation this class permits is {@link #supersede(UUID)}.
 */
@Entity
@Table(name = "question")
public class Question {

    @Id
    private UUID id;

    @Column(name = "bank_id", nullable = false)
    private UUID bankId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "lineage_id", nullable = false)
    private UUID lineageId;

    @Column(nullable = false)
    private int version;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuestionType type;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(length = 8)
    private String difficulty;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "retired_at")
    private Instant retiredAt;

    protected Question() {
    }

    public Question(UUID id, UUID bankId, UUID workspaceId, UUID lineageId, int version,
                    QuestionType type, String prompt, String payload, String contentHash,
                    String difficulty, UUID createdBy) {
        this.id = id;
        this.bankId = bankId;
        this.workspaceId = workspaceId;
        this.lineageId = lineageId;
        this.version = version;
        this.type = type;
        this.prompt = prompt;
        this.payload = payload;
        this.contentHash = contentHash;
        this.difficulty = difficulty;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getBankId() { return bankId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getLineageId() { return lineageId; }
    public int getVersion() { return version; }
    public UUID getSupersededBy() { return supersededBy; }
    public QuestionType getType() { return type; }
    public String getPrompt() { return prompt; }
    public String getContentHash() { return contentHash; }
    public String getDifficulty() { return difficulty; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getRetiredAt() { return retiredAt; }

    /** Deserialises the stored payload. Never exposed to a player. */
    public Payload payload() {
        return type.parsePayload(payload);
    }

    /** The only permitted mutation: pointing at the version that replaced this one. */
    public void supersede(UUID replacementId) {
        this.supersededBy = replacementId;
    }

    public void retire() {
        this.retiredAt = Instant.now();
    }
}
