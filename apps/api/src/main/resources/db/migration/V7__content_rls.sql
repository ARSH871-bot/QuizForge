-- V7: Tenant isolation for content, matching the pattern established in V5.
--
-- The application assumes the NOBYPASSRLS role quizforge_app for the duration
-- of any transaction carrying a tenant (see TenantAwareDataSource), which is
-- what makes these policies apply at all. Without that role switch they would
-- be silently skipped, because the application connects as a superuser.

ALTER TABLE question_bank ENABLE ROW LEVEL SECURITY;
ALTER TABLE question_bank FORCE ROW LEVEL SECURITY;

CREATE POLICY question_bank_tenant_isolation ON question_bank
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

ALTER TABLE question ENABLE ROW LEVEL SECURITY;
ALTER TABLE question FORCE ROW LEVEL SECURITY;

-- question carries workspace_id denormalised from its bank precisely so this
-- policy needs no join. A policy that joins runs on every row of every query.
CREATE POLICY question_tenant_isolation ON question
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- V5 granted privileges on the tables that existed then. The content tables
-- were created afterwards by V6, so they need granting explicitly; the
-- ALTER DEFAULT PRIVILEGES in V5 only covers tables created by the role that
-- issued it.
GRANT SELECT, INSERT, UPDATE, DELETE ON question_bank, question TO quizforge_app;
