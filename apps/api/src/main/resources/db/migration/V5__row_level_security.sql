-- V5: Row-Level Security. Tenant scoping is enforced by PostgreSQL rather than
-- by application predicates, so a forgotten WHERE clause returns nothing
-- instead of another workspace's data.

-- A dedicated, non-superuser role. RLS policies do not apply to superusers or
-- to roles with BYPASSRLS, so running the application as the owner would make
-- every policy below decorative.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'quizforge_app') THEN
        CREATE ROLE quizforge_app NOLOGIN NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO quizforge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO quizforge_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO quizforge_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO quizforge_app;

-- Returns the workspace set for the current transaction, or NULL when unset.
-- STABLE so the planner may cache it within a statement.
CREATE OR REPLACE FUNCTION current_workspace_id() RETURNS UUID AS $$
    SELECT NULLIF(current_setting('app.workspace_id', true), '')::UUID;
$$ LANGUAGE SQL STABLE;

ALTER TABLE api_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_key FORCE ROW LEVEL SECURITY;

CREATE POLICY api_key_tenant_isolation ON api_key
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

ALTER TABLE membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership FORCE ROW LEVEL SECURITY;

CREATE POLICY membership_tenant_isolation ON membership
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- account, workspace and session are deliberately NOT tenant-scoped: they are
-- read during authentication, before a workspace is known. They are protected
-- by application-layer authorization instead.

-- Allow the owner to assume the restricted role for the duration of a
-- transaction. This is what lets the application enforce RLS without a second
-- set of credentials: SET LOCAL ROLE quizforge_app drops superuser status, the
-- policies above then apply, and the role reverts on commit or rollback.
GRANT quizforge_app TO CURRENT_USER;
