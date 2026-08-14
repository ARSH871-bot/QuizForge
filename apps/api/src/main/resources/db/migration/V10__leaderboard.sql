-- V10: Materialised standings, one row per (tournament, account).
--
-- Raw aggregates are stored rather than a single resolved score, and the
-- tournament's scoring policy is applied at read time. That way changing a
-- policy takes effect immediately instead of requiring every historical
-- standing to be recomputed.

CREATE TABLE standing (
    tournament_id    UUID        NOT NULL REFERENCES tournament (id) ON DELETE CASCADE,
    account_id       UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    workspace_id     UUID        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,

    attempts         INTEGER     NOT NULL,
    best_score       INTEGER     NOT NULL,
    total_score      INTEGER     NOT NULL,
    first_score      INTEGER     NOT NULL,
    last_score       INTEGER     NOT NULL,
    out_of           INTEGER     NOT NULL,

    first_graded_at  TIMESTAMPTZ NOT NULL,
    last_graded_at   TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (tournament_id, account_id),
    CONSTRAINT ck_standing_attempts CHECK (attempts >= 1),
    CONSTRAINT ck_standing_out_of CHECK (out_of >= 1)
);

-- Ranking reads: highest first, then earliest. The tie-break is deliberate —
-- the player who got there first ranks higher.
CREATE INDEX idx_standing_ranking
    ON standing (tournament_id, best_score DESC, first_graded_at ASC);

ALTER TABLE standing ENABLE ROW LEVEL SECURITY;
ALTER TABLE standing FORCE ROW LEVEL SECURITY;

CREATE POLICY standing_tenant_isolation ON standing
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON standing TO quizforge_app;
