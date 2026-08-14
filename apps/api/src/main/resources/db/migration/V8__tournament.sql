-- V8: Tournaments. A tournament is a scheduled run of questions drawn from a
-- bank, open for a window.
--
-- State (SCHEDULED / OPEN / CLOSED) is deliberately NOT stored. It is derived
-- from opens_at and closes_at against the clock, so no scheduled job is needed
-- to transition anything and a row can never disagree with the calendar.

CREATE TABLE tournament (
    id                  UUID         PRIMARY KEY,
    workspace_id        UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    bank_id             UUID         NOT NULL REFERENCES question_bank (id),
    name                VARCHAR(120) NOT NULL,
    slug                VARCHAR(140) NOT NULL,

    opens_at            TIMESTAMPTZ  NOT NULL,
    closes_at           TIMESTAMPTZ  NOT NULL,

    question_count      INTEGER      NOT NULL,
    time_limit_seconds  INTEGER,

    -- Per ADR 0003: defaults preserve single-attempt semantics while making
    -- practice modes a configuration change rather than a migration.
    max_attempts        INTEGER      NOT NULL DEFAULT 1,
    scoring_policy      VARCHAR(8)   NOT NULL DEFAULT 'FIRST',

    created_by          UUID         NOT NULL REFERENCES account (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_tournament_slug UNIQUE (workspace_id, slug),
    CONSTRAINT ck_tournament_window CHECK (closes_at > opens_at),
    CONSTRAINT ck_tournament_question_count CHECK (question_count >= 1),
    CONSTRAINT ck_tournament_max_attempts CHECK (max_attempts >= 1),
    CONSTRAINT ck_tournament_time_limit CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0),
    CONSTRAINT ck_tournament_scoring_policy CHECK (scoring_policy IN
        ('BEST', 'FIRST', 'LAST', 'AVERAGE'))
);

CREATE INDEX idx_tournament_workspace ON tournament (workspace_id);
CREATE INDEX idx_tournament_window    ON tournament (opens_at, closes_at);
CREATE INDEX idx_tournament_bank      ON tournament (bank_id);

-- Tenant isolation, matching V5 and V7.
ALTER TABLE tournament ENABLE ROW LEVEL SECURITY;
ALTER TABLE tournament FORCE ROW LEVEL SECURITY;

CREATE POLICY tournament_tenant_isolation ON tournament
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- V5's ALTER DEFAULT PRIVILEGES only covers tables created by the role that
-- issued it, so tables added later must be granted explicitly.
GRANT SELECT, INSERT, UPDATE, DELETE ON tournament TO quizforge_app;
