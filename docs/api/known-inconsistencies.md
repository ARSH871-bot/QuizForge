# API inconsistencies found, and what happened to them

Found while writing `openapi.yaml` for M4 Task 1, and **all fixed in Task 2**
before anything consumed the contract.

Kept as a record rather than deleted, because the reasoning is what matters:
each was cheap to fix on the day it was found and would have been a breaking
change a milestone later. The gate that now prevents a repeat is described in
[ADR 0011](../adr/0011-generated-interfaces-not-generated-controllers.md).

## Why they were fixed immediately

`/v1` is additive-only: no field removed, no field's type changed. That rule
starts binding the moment something consumes the contract.

When these were found, nothing did. No SDK existed, the spec was not published,
there were no external users. Changing `attemptId` from a bare UUID to `att_…`
cost one commit.

`oasdiff` scores the same set of changes like this:

```
7 changes: 7 error, 0 warning, 0 info
```

Every one of those would have been a breaking change to somebody's integration
had they been left until after the SDK shipped in Task 9.

---

## 1. Identifiers were prefixed in some responses and bare UUIDs in others — **fixed**

| Field | Was | Now |
|---|---|---|
| `AttemptResult.attemptId` | `018f3a5c-1c2b-…` | `att_…` |
| `PlayableQuestion.questionId` | bare UUID | `qst_…` |
| `LeaderboardEntry.accountId` | bare UUID | `acc_…` |

The worst was `attemptId`, because the same attempt was identified two ways
inside one workflow:

```
POST /v1/tournaments/{id}/attempts   -> { "id": "att_01a0136f…" }
GET  /v1/attempts/att_01a0136f…      -> { "attemptId": "018f3a5c-1c2b-…" }
```

A client could not round-trip the value it had just been handed.

The cause was mechanical: `AttemptResult`, `PlayableQuestion` and
`LeaderboardEntry` were **application-layer records returned straight from
controllers**, so their internal `UUID` fields serialised verbatim. The
boundary rule — identifiers are rendered only at the API boundary — was real;
those three types were not at the boundary, they leaked through it.

Now every response body is a model generated from the contract, and controllers
map into it explicitly. The class of defect is closed, not just the three
instances: an internal type can no longer *be* the public contract, because the
public contract is generated.

## 2. A valid session with the wrong workspace returned 401 — **fixed**

Naming a workspace the account was not a member of returned
`401 AUTHENTICATION_REQUIRED`. The filter returned before setting a principal,
so a caller holding a perfectly good session was told they were not
authenticated — and would re-authenticate, get `401` again, with nothing
pointing at the header that was actually wrong.

Now `403 PERMISSION_DENIED`. The session authenticates; the workspace is
refused separately.

Deliberately not `404`: the workspace id came from the caller, so declining to
confirm it exists protects nothing they did not already know.

## 3. List endpoints returned bare arrays — **fixed**

`GET /v1/tournaments` and `GET /v1/tournaments/{id}/standings` returned `[...]`.

They now return `{ "data": [...], "nextCursor": null }`.

`nextCursor` is always `null` — cursor pagination arrives in Task 6. The field
exists now so that filling it in will be additive. Had the envelope waited for
Task 6, adopting it would have changed the **top-level type** of two live
endpoints, which is the one breaking change no client survives. `oasdiff` names
it exactly:

```
the response's body `type` changed from `array<object>` to `object`
```

## 4. `limit` was silently clamped — **fixed**

`?limit=1000` returned 100 rows with no indication of correction, so a client
could not distinguish "clamped" from "only 100 exist" — and concluded it held
the whole leaderboard. `limit=0` and `limit=-5` silently became 1.

Now `400 INVALID_REQUEST`. Silent correction of input produces wrong answers
instead of errors, which is the worse failure.

The constraint is enforced twice, deliberately: the generated interface carries
`@Min(1) @Max(100)` from the contract, and the controller checks again. The
first is the contract's; the second survives if the endpoint is ever called
from somewhere the generated interface does not cover.

## 5. `POST /v1/auth/request-password-reset` validated nothing — **fixed**

The handler took `Map<String, String>`, so `{}` and `{"emial": …}` were both
`202` — a success status for a request that named no address.

Now a typed `PasswordResetRequest` with `@Email @NotBlank`. A malformed request
is `400`.

The always-`202` behaviour is unchanged and must stay: distinguishing a
registered from an unregistered address would make this an account-enumeration
oracle. Only the *shape* is validated; the *existence* of the account is still
concealed. A test asserts both halves.

## 6. `MessageResponse` carried nothing machine-readable — **fixed**

The endpoint returned one English sentence intended for display. Any client
needing to react had to match on prose that would be reworded or localised.

Now `202` with an empty body, and the schema is deleted so the shape cannot be
copied into the twenty-five endpoints M4 still has to build.

## 7. `emailVerified` is always false — **not a defect, left alone**

Populated from `getEmailVerifiedAt() != null`, and nothing sets that column
because email verification is not implemented.

Documented as inert rather than removed. It is genuinely reserved. A client
that gates behaviour on it today gates it off forever, which is why the
contract says so plainly instead of leaving the reader to infer it.

---

## What found them

Not a code review. Writing the contract.

Documenting an endpoint forces you to state its response shape, its parameters
and its authorization — for every endpoint in turn, including the ones nobody
has thought about since they were written. Reviewing the same code for defects
had not surfaced any of these, and had not surfaced the two cross-tenant
defects recorded in [ADR 0010](../adr/0010-workspace-scope-is-required-before-a-handler-runs.md)
either.
