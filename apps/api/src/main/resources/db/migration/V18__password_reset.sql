-- V18: Password reset tokens.
--
-- Stored as a SHA-256 digest, like sessions: the token has 256 bits of entropy
-- from a CSPRNG, so the digest is enough, and a leaked table cannot be replayed.
-- Account-scoped, not tenant-scoped, so no row-level security - the same as
-- account and session.

CREATE TABLE password_reset_token (
    id          UUID        PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    CONSTRAINT uk_password_reset_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_password_reset_account ON password_reset_token (account_id, created_at DESC);
