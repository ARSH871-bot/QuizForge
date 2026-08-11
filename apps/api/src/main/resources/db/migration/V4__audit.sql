-- V4: Append-only audit log for privileged actions.

CREATE TABLE audit_event (
    id            UUID        PRIMARY KEY,
    workspace_id  UUID        REFERENCES workspace (id) ON DELETE CASCADE,
    actor_id      UUID        REFERENCES account (id),
    action        VARCHAR(64) NOT NULL,
    target_type   VARCHAR(64),
    target_id     UUID,
    detail        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_workspace_time ON audit_event (workspace_id, created_at DESC);
CREATE INDEX idx_audit_actor          ON audit_event (actor_id);

-- Append-only enforced by the database. An audit log the application can
-- rewrite proves nothing to an auditor.
CREATE OR REPLACE FUNCTION reject_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_is_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
