# 8. Row-Level Security is enforced by assuming a restricted role

Date: 2026-08-12

## Status

Accepted — policies in place; runtime enforcement not yet wired (see Consequences)

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

**Negative, and important: the application does not yet assume this role at
runtime.** The policies and the proof exist; the connection-level wiring does
not. Until it is done, RLS provides no protection in a running application —
only the application-layer checks in the service classes do.

Wiring it is not a one-line change. `SET LOCAL` requires an open transaction,
while a session-level `SET ROLE` would leak across pooled connections. The
correct implementation hooks connection preparation so both settings are
applied inside the transaction that will use them. Tracked separately rather
than half-implemented, because a security control that is present but inert is
worse than one that is visibly absent: it invites the assumption of protection
that is not there.
