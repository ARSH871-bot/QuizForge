-- V2: Identity and tenancy. Introduces the account/workspace/membership
-- triangle that every later module scopes itself to.

CREATE TABLE account (
    id                  UUID PRIMARY KEY,
    email               VARCHAR(320) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    display_name        VARCHAR(120) NOT NULL,
    email_verified_at   TIMESTAMPTZ,
    mfa_secret          VARCHAR(255),
    mfa_enabled_at      TIMESTAMPTZ,
    failed_login_count  INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0
);

-- Email uniqueness must be case-insensitive: Alice@example.com and
-- alice@example.com are the same person to every mail server on earth.
CREATE UNIQUE INDEX uk_account_email_lower ON account (LOWER(email));

CREATE TABLE workspace (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(64)  NOT NULL,
    created_by  UUID         NOT NULL REFERENCES account (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_workspace_slug UNIQUE (slug)
);

CREATE TABLE membership (
    id            UUID        PRIMARY KEY,
    account_id    UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    workspace_id  UUID        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    role          VARCHAR(16) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_membership_account_workspace UNIQUE (account_id, workspace_id),
    CONSTRAINT ck_membership_role CHECK (role IN ('OWNER', 'ADMIN', 'EDITOR', 'VIEWER'))
);

CREATE INDEX idx_membership_account   ON membership (account_id);
CREATE INDEX idx_membership_workspace ON membership (workspace_id);

-- A workspace must always retain at least one owner. Enforced in the
-- application layer on removal and role change; this partial index makes the
-- owner lookup cheap enough to run on every such operation.
CREATE INDEX idx_membership_owners ON membership (workspace_id) WHERE role = 'OWNER';
