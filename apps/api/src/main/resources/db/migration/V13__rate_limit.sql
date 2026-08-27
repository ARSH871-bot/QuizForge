-- V13: Per-caller rate limiting, as a token bucket in Postgres.
--
-- A bucket rather than a fixed window because a window lets a caller spend its
-- whole allowance in the last second of one window and again in the first
-- second of the next, which is twice the intended rate at exactly the moment it
-- hurts. A bucket refills continuously, so a burst costs what it costs and then
-- has to wait.
--
-- In Postgres rather than Redis: ADR 0004 rules Redis out, and the state here
-- is one small row per caller updated once per request. That is a workload
-- Postgres handles without complaint, and it is one fewer thing to operate.

-- Per-workspace override. Null means "use the configured default", so raising a
-- customer's limit is one UPDATE and needs no deploy. M7's billing tiers change
-- this column.
ALTER TABLE workspace
    ADD COLUMN rate_limit_per_minute INTEGER,
    ADD CONSTRAINT ck_workspace_rate_limit
        CHECK (rate_limit_per_minute IS NULL OR rate_limit_per_minute > 0);

CREATE TABLE rate_limit_bucket (
    -- The API key when one is presented, otherwise the account. Rate limiting
    -- follows the credential, not the person: two keys in one workspace get
    -- their own allowances, so a runaway script does not starve the dashboard.
    subject_id    UUID             PRIMARY KEY,

    workspace_id  UUID             NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,

    -- Fractional, because refill is continuous. Storing whole tokens would
    -- round away every partial refill and quietly throttle callers harder than
    -- the advertised rate.
    tokens        DOUBLE PRECISION NOT NULL,

    -- When `tokens` was last brought up to date. Refill is computed from the
    -- gap rather than by a background job, so an idle bucket costs nothing.
    refilled_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),

    CONSTRAINT ck_bucket_tokens CHECK (tokens >= 0)
);

CREATE INDEX idx_rate_limit_bucket_workspace ON rate_limit_bucket (workspace_id);

ALTER TABLE rate_limit_bucket ENABLE ROW LEVEL SECURITY;
ALTER TABLE rate_limit_bucket FORCE ROW LEVEL SECURITY;

CREATE POLICY rate_limit_bucket_tenant_isolation ON rate_limit_bucket
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON rate_limit_bucket TO quizforge_app;
