# 10. Require workspace scope before a handler runs

Date: 2026-08-18

## Status

Accepted.

## Context

Tenant isolation rests on PostgreSQL Row-Level Security
([ADR 0008](0008-rls-enforced-by-assumed-role.md)). Those policies compare
`workspace_id` against `current_workspace_id()`, and they are only enforced
because `TenantAwareDataSource` drops the connection to the `NOBYPASSRLS` role
`quizforge_app` at the start of each transaction.

It does that **only when a tenant is in scope**. That was deliberate and
documented: authentication has to read `account` before any workspace is known,
and Flyway has to run migrations as the owner. So with no tenant, the
connection is handed through untouched — and PostgreSQL skips RLS entirely for
superusers.

The datasource's own comment stated the consequence plainly:

> When no tenant is in scope … the connection is handed through untouched and
> operates as the owner. Those paths are protected by application-layer
> authorization instead.

That sentence is a load-bearing assumption, and nothing enforced it.

`TenantContext` is populated only when the `X-QuizForge-Workspace` header is
present (or an API key supplies the workspace). So **any authenticated caller
could omit the header and get an unfiltered connection.** Whether that leaked
data depended entirely on whether the individual handler happened to check
authorization itself.

Two did not. Both were confirmed by test before being fixed:

| Endpoint | Result with the header omitted |
|---|---|
| `GET /v1/tournaments/{id}/standings` | `200` with **another workspace's standings** |
| `POST /v1/tournaments/{id}/attempts` | `201` — an attempt **created** on another workspace's tournament |

The second is a write. Neither required anything more exotic than knowing a
tournament id and leaving a header off.

`TournamentController` was unaffected, because it happened to check
`principal.workspaceId()` — for usability, not for security. The isolation of
the entire `/v1` surface rested on that coincidence.

## Decision

An authenticated request to `/v1/**` that has no workspace in scope is refused
with `400 INVALID_REQUEST` by `WorkspaceScopeFilter`, before it reaches a
handler.

Paths that are legitimately account-scoped are listed explicitly in the filter.
Today that is `/v1/auth/**` — registration, login, logout, `/me` and password
reset, none of which can have a workspace because some run before the account
has one.

The check is in a filter, not in each controller, and that is the whole point.
Per-controller checks were the previous design; they were correct in three
places out of five, and the two failures were invisible because the endpoints
worked — they just worked for everybody. M4 adds roughly twenty-five more
endpoints, so the question is not whether a per-endpoint rule would be applied
correctly twenty-five times but how soon it would not be.

The unauthenticated case is deliberately left alone: those requests already
fail with `401` at the authentication entry point, which is the more useful
answer, and pre-empting it with `400` would tell an anonymous caller that the
path exists.

## Consequences

Isolation no longer depends on each handler remembering. A new endpoint is
tenant-scoped by default, and making one workspace-free requires editing an
explicit list — which is a visible act in review rather than an omission.

The two confirmed defects are closed, with regression tests that first asserted
the leak and then asserted the fix, so the failure was demonstrated rather than
assumed.

**This does not make RLS fail closed.** The underlying property — no tenant
means an unfiltered connection — is unchanged. A background job, a scheduled
task, or any future code path that runs outside a servlet request still gets an
owner connection with no policies applied. The filter guards the HTTP surface,
which is where the exposure was, and nothing else.

The stronger fix is to make `TenantAwareDataSource` assume the restricted role
even with no workspace set, so an absent tenant yields *no rows* rather than
*all rows*. That was not done here because it requires auditing every
non-request path — authentication reads, Flyway, the expiry sweep — for the
grants they would then need, and doing that badly would break startup rather
than leak data. It is the correct eventual design and is recorded as follow-up
rather than pretended away.

Worth stating for the next audit: **both defects were found by writing the
OpenAPI contract, not by reading the code.** Documenting an endpoint forces you
to state what its authorization is, and doing that for every endpoint in turn
surfaced the two where the answer was "nothing". Reviewing the same code for
bugs had not.
