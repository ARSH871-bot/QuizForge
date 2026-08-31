-- V15: Close tenant-isolation gaps on attempt children and audit events.
--
-- `attempt` already carries workspace_id and has RLS. Its child tables do not
-- duplicate that column, so their policies inherit scope through the parent
-- attempt. `audit_event` carries workspace_id directly.

ALTER TABLE attempt_question ENABLE ROW LEVEL SECURITY;
ALTER TABLE attempt_question FORCE ROW LEVEL SECURITY;

CREATE POLICY attempt_question_tenant_isolation ON attempt_question
    USING (EXISTS (
        SELECT 1
        FROM attempt
        WHERE attempt.id = attempt_question.attempt_id
          AND attempt.workspace_id = current_workspace_id()
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM attempt
        WHERE attempt.id = attempt_question.attempt_id
          AND attempt.workspace_id = current_workspace_id()
    ));

ALTER TABLE response ENABLE ROW LEVEL SECURITY;
ALTER TABLE response FORCE ROW LEVEL SECURITY;

CREATE POLICY response_tenant_isolation ON response
    USING (EXISTS (
        SELECT 1
        FROM attempt
        WHERE attempt.id = response.attempt_id
          AND attempt.workspace_id = current_workspace_id()
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM attempt
        WHERE attempt.id = response.attempt_id
          AND attempt.workspace_id = current_workspace_id()
    ));

ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_event FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_event_tenant_isolation ON audit_event
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
