-- V3: Authentication material. Neither table stores a usable credential: both
-- keep only a SHA-256 digest, so a database disclosure does not yield tokens
-- that can be replayed.

CREATE TABLE session (
    id            UUID        PRIMARY KEY,
    account_id    UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    token_hash    VARCHAR(64) NOT NULL,
    user_agent    VARCHAR(512),
    ip_address    VARCHAR(45),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT uk_session_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_session_account ON session (account_id);
CREATE INDEX idx_session_expiry  ON session (expires_at) WHERE revoked_at IS NULL;

CREATE TABLE api_key (
    id            UUID         PRIMARY KEY,
    workspace_id  UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    created_by    UUID         NOT NULL REFERENCES account (id),
    name          VARCHAR(120) NOT NULL,
    token_hash    VARCHAR(64)  NOT NULL,
    last_four     VARCHAR(4)   NOT NULL,
    environment   VARCHAR(8)   NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT uk_api_key_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_api_key_environment CHECK (environment IN ('live', 'test'))
);

CREATE INDEX idx_api_key_workspace ON api_key (workspace_id) WHERE revoked_at IS NULL;
