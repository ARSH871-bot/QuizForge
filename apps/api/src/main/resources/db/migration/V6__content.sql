-- V6: Question banks and questions.
--
-- The legacy quiz schema (V1) already owns the name "question". Rather than
-- compromise the new model's naming for a table that is deleted in M3, the
-- legacy one is renamed out of the way. Its entity mapping is updated in the
-- same commit, including an explicit @CollectionTable, because Hibernate
-- derives the element-collection table name from the owning table and would
-- otherwise look for legacy_question_options.
ALTER TABLE question RENAME TO legacy_question;
ALTER INDEX IF EXISTS idx_question_quiz_id RENAME TO idx_legacy_question_quiz_id;
--
-- A question row is immutable. Editing inserts a new row sharing lineage_id
-- with version + 1, and stamps superseded_by on the previous row. Tournaments
-- reference a concrete question.id, so what a player saw is pinned by
-- construction rather than by remembering to pin it.

CREATE TABLE question_bank (
    id            UUID         PRIMARY KEY,
    workspace_id  UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name          VARCHAR(120) NOT NULL,
    description   VARCHAR(500),
    created_by    UUID         NOT NULL REFERENCES account (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    archived_at   TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_question_bank_name UNIQUE (workspace_id, name)
);

CREATE INDEX idx_question_bank_workspace
    ON question_bank (workspace_id) WHERE archived_at IS NULL;

CREATE TABLE question (
    id             UUID         PRIMARY KEY,
    bank_id        UUID         NOT NULL REFERENCES question_bank (id) ON DELETE CASCADE,
    workspace_id   UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,

    -- Stable identity across versions. The first version's id is reused as the
    -- lineage_id, so a lineage needs no separate table.
    lineage_id     UUID         NOT NULL,
    version        INTEGER      NOT NULL,
    superseded_by  UUID         REFERENCES question (id),

    type           VARCHAR(16)  NOT NULL,
    prompt         TEXT         NOT NULL,
    payload        JSONB        NOT NULL,

    -- VARCHAR, never CHAR: Hibernate rejects CHAR as bpchar against a String
    -- field, and Postgres blank-pads CHAR, which silently breaks comparison.
    content_hash   VARCHAR(64)  NOT NULL,

    difficulty     VARCHAR(8),
    created_by     UUID         NOT NULL REFERENCES account (id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at     TIMESTAMPTZ,

    CONSTRAINT uk_question_lineage_version UNIQUE (lineage_id, version),
    CONSTRAINT ck_question_type CHECK (type IN
        ('SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE', 'NUMERIC', 'SHORT_TEXT')),
    CONSTRAINT ck_question_difficulty CHECK (difficulty IS NULL OR difficulty IN
        ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT ck_question_version_positive CHECK (version >= 1)
);

-- The hot read path: current, non-retired questions in a bank.
CREATE INDEX idx_question_current ON question (bank_id)
    WHERE superseded_by IS NULL AND retired_at IS NULL;

CREATE INDEX idx_question_lineage   ON question (lineage_id);
CREATE INDEX idx_question_workspace ON question (workspace_id);

-- De-duplication. Scoped to the bank: the same question legitimately appears
-- in two banks, but never twice in one.
CREATE UNIQUE INDEX uk_question_bank_content
    ON question (bank_id, content_hash) WHERE superseded_by IS NULL;
