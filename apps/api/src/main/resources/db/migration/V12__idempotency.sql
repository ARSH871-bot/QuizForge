-- V12: Idempotency keys, so a mutating request can be safely retried.
--
-- A client that does not know whether its request arrived - a timeout, a
-- dropped connection, a retrying HTTP library - has two bad options without
-- this: retry and risk doing the thing twice, or not retry and risk not doing
-- it at all. The key makes the retry safe, which means clients stop having to
-- choose.
--
-- Keyed on (workspace_id, key) so two workspaces cannot collide on a key one of
-- them chose, and so the record is scoped to the same tenant as the work it
-- describes.

CREATE TABLE idempotency_key (
    workspace_id     UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,

    -- Client-chosen. Length capped because it is an identifier, not a payload;
    -- a UUID is the obvious choice and fits comfortably.
    key              VARCHAR(255) NOT NULL,

    -- SHA-256 of method, path and body. The same key arriving with a different
    -- request is a client bug - most often a key that was reused rather than
    -- regenerated - and is refused rather than silently answered with the
    -- earlier response, which would be the wrong answer to the question asked.
    request_hash     CHAR(64)     NOT NULL,

    -- Null while the original request is still in flight. That is what makes
    -- the row a claim as well as a record: it is inserted before the work runs,
    -- so a concurrent retry finds it and does not start the work again.
    response_status  INTEGER,
    response_body    TEXT,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (workspace_id, key),

    CONSTRAINT ck_idempotency_status CHECK (
        response_status IS NULL OR (response_status BETWEEN 100 AND 599)
    )
);

-- The purge scans by age.
CREATE INDEX idx_idempotency_created_at ON idempotency_key (created_at);

ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_key FORCE ROW LEVEL SECURITY;

CREATE POLICY idempotency_tenant_isolation ON idempotency_key
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_key TO quizforge_app;
