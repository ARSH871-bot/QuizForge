# 8. Row-Level Security is enforced by assuming a restricted role

Date: 2026-08-12

## Status

Accepted — policies in place and enforced at runtime

## Context

Tenant isolation is enforced by PostgreSQL Row-Level Security so that a
forgotten `WHERE workspace_id = ?` returns nothing rather than another
customer's data.

Writing the policies turned out to be the easy half. **RLS is silently skipped
for superusers**, and for table owners unless `FORCE ROW LEVEL SECURITY` is
set. The application connects as `quizforge`, which is a superuser in both the
local Compose stack and the Testcontainers image. Every policy was therefore
present and enforcing nothing.

This was caught only because a test was written to prove isolation rather than
to assert that a policy exists. The original test checked
`pg_roles.rolbypassrls` for `quizforge_app` — a role the application never
connects as. It passed, and proved nothing.

## Decision

Policies apply to a dedicated `quizforge_app` role created `NOBYPASSRLS`. The
owner is granted membership of that role, so a transaction can assume it:

```sql
SET LOCAL ROLE quizforge_app;
SET LOCAL app.workspace_id = '<uuid>';
```

`SET LOCAL` scopes both to the transaction, so a pooled connection cannot
carry one request's tenant or role into the next. No second set of database
credentials is needed, and Flyway continues to run as the owner because
migrations require DDL the restricted role does not have.

Tables covered: `api_key`, `membership`. Deliberately excluded: `account`,
`workspace`, `session` — they are read during authentication, before a
workspace is known, and are protected by application-layer authorization.

## Consequences

Positive: a missing tenant predicate becomes an empty result rather than a
cross-tenant disclosure. The mechanism is proven by a test that inserts a row
for one workspace and asserts it is invisible while another is in scope.

Runtime enforcement is implemented (#23). `TenantAwareDataSource` wraps the
pool and hooks `Connection.setAutoCommit(false)` — the moment Spring opens a
transaction — to issue both statements. That hook is the only correct one:
`SET LOCAL` outside a transaction is a no-op, and a session-level `SET ROLE`
would leak across pooled connections.

`RlsRuntimeEnforcementTest` proves it end to end: it asserts the transaction
runs as `quizforge_app`, and that a query explicitly asking for another
workspace's rows returns nothing.

Negative: every transaction carrying a tenant pays two extra round trips. At
the expected scale this is not measurable, but it is real, and it is the price
of a control that fails closed.

Negative: paths with no tenant in scope — authentication, and Flyway
migrations — still run as the owner and are not covered by RLS. That is
deliberate; `account`, `workspace` and `session` are read before a workspace is
known. They rely on application-layer authorization.
