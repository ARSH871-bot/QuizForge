-- V9: Attempts, the frozen question set, and responses.
--
-- An attempt is durable state rather than a marker row. That is what makes
-- play resumable after a disconnect, submission idempotent, and time limits
-- enforceable — the three things the prototype could not do.

CREATE TABLE attempt (
    id                 UUID        PRIMARY KEY,
    tournament_id      UUID        NOT NULL REFERENCES tournament (id) ON DELETE CASCADE,
    workspace_id       UUID        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    account_id         UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,

    state              VARCHAR(16) NOT NULL DEFAULT 'STARTED',

    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at       TIMESTAMPTZ,
    graded_at          TIMESTAMPTZ,

    -- Computed once from the server clock at creation. Never accepted from a
    -- request: a client-supplied expiry is not a time limit.
    expires_at         TIMESTAMPTZ,

    -- Frozen at creation. The prototype graded against every question in the
    -- quiz while players only ever saw ten; freezing the denominator here is
    -- what makes that impossible.
    score_numerator    INTEGER     NOT NULL DEFAULT 0,
    score_denominator  INTEGER     NOT NULL,

    version            BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ck_attempt_state CHECK (state IN ('STARTED', 'GRADED', 'EXPIRED')),
    CONSTRAINT ck_attempt_denominator CHECK (score_denominator >= 1),
    CONSTRAINT ck_attempt_numerator CHECK (score_numerator >= 0
                                           AND score_numerator <= score_denominator)
);

CREATE INDEX idx_attempt_tournament_account ON attempt (tournament_id, account_id);
CREATE INDEX idx_attempt_workspace          ON attempt (workspace_id);

-- Finds attempts the expiry sweep needs to close, without scanning the rest.
CREATE INDEX idx_attempt_open_expiring ON attempt (expires_at)
    WHERE state = 'STARTED' AND expires_at IS NOT NULL;

-- The question set, frozen at creation with its per-attempt option order.
-- Storing the order is what lets a replay show exactly what the player saw
-- while two players still get different orders.
CREATE TABLE attempt_question (
    attempt_id    UUID    NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,
    position      INTEGER NOT NULL,
    question_id   UUID    NOT NULL REFERENCES question (id),
    option_order  JSONB   NOT NULL DEFAULT '[]'::jsonb,

    PRIMARY KEY (attempt_id, position),
    CONSTRAINT uk_attempt_question UNIQUE (attempt_id, question_id),
    CONSTRAINT ck_attempt_question_position CHECK (position >= 1)
);

CREATE TABLE response (
    id           UUID        PRIMARY KEY,
    attempt_id   UUID        NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,
    question_id  UUID        NOT NULL REFERENCES question (id),

    given        TEXT,
    correct      BOOLEAN     NOT NULL,
    answered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Answering the same question twice replaces rather than duplicates, which
    -- is what makes the per-question flow safe to retry.
    CONSTRAINT uk_response_attempt_question UNIQUE (attempt_id, question_id)
);

CREATE INDEX idx_response_attempt ON response (attempt_id);

-- Tenant isolation. attempt carries workspace_id denormalised so the policy
-- needs no join; its children are reached only through it.
ALTER TABLE attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE attempt FORCE ROW LEVEL SECURITY;

CREATE POLICY attempt_tenant_isolation ON attempt
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON attempt, attempt_question, response TO quizforge_app;
