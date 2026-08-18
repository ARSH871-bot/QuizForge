# Known API inconsistencies

Found while writing `openapi.yaml` for M4 Task 1. Every one of these is
**documented in the contract as it actually behaves**, not as it ought to
behave — a contract that describes an intention is worse than no contract,
because it is believed.

They are recorded here rather than fixed on the spot because fixing them
changes response shapes, and the point of Task 1 was to find out what the
shapes are. They should be fixed in **Task 2**, while the fix is still free.

## Why the timing matters

`/v1` is additive-only: no field removed, no field's type changed. That rule
starts biting the moment something consumes the contract.

Right now **nothing does.** No SDK exists, the spec is not published, and there
are no external users. Changing `attemptId` from a bare UUID to `att_…` today
costs one commit. After Task 9 ships an SDK it is a breaking change to
somebody's integration, and after Task 10 publishes the spec it is a breaking
change to somebody's hand-written client too.

So the cheapest moment to fix all of these is before Task 2 generates
interfaces from this document.

---

## 1. Identifiers are prefixed in some responses and bare UUIDs in others

Three response fields return raw UUIDs where every other identifier in the API
is a prefixed `TypeId`:

| Field | Returns | Should return |
|---|---|---|
| `AttemptResult.attemptId` | `018f3a5c-1c2b-7d3e-…` | `att_018f3a5c1c2b7d3e…` |
| `PlayableQuestion.questionId` | bare UUID | `qst_…` |
| `LeaderboardEntry.accountId` | bare UUID | `acc_…` |

The worst of these is `attemptId`, because **the same attempt is identified two
different ways within one workflow**:

```
POST /v1/tournaments/{id}/attempts   -> { "id": "att_01a0136f…" }
GET  /v1/attempts/att_01a0136f…      -> { "attemptId": "018f3a5c-1c2b-…" }
```

A client cannot round-trip the value it was just handed. It has to know that
one field is prefixed and the other is not, and convert between them.

The cause is mechanical: `AttemptStarted` is a web DTO built in the controller,
which calls `TypeId.render`. `AttemptResult`, `PlayableQuestion` and
`LeaderboardEntry` are **application-layer records returned directly from the
controller**, so they serialise their internal `UUID` fields verbatim. The
boundary rule — identifiers are rendered only at the API boundary — is real,
but these three types are not at the boundary; they leaked through it.

**Fix:** give each a web DTO, as `AccountResponse` already has. That also stops
internal types being part of the public contract, which is the underlying
problem.

## 2. A valid session with the wrong workspace returns 401, not 403

Naming a workspace the account does not belong to, or sending a malformed
`X-QuizForge-Workspace` value, returns `401 AUTHENTICATION_REQUIRED`.

```java
if (workspaceId != null && role == null) {
    return;   // membership is required to act in a workspace
}
```

The filter returns before setting the principal, so the request is
indistinguishable from one with no credential at all. The caller has a
perfectly good session and is told they are not authenticated.

The practical cost is a developer who sees `401`, concludes their session
expired, re-authenticates, and gets `401` again — with nothing pointing at the
header, which is the actual problem.

`403 PERMISSION_DENIED` is the accurate answer. Note it must not be `404`: the
workspace id came from the caller, so refusing to confirm its existence
protects nothing they did not already know.

**Fix:** authenticate the session first, then reject the workspace separately.

## 3. List endpoints return bare arrays

`GET /v1/tournaments` and `GET /v1/tournaments/{id}/standings` return
`[...]` directly.

Task 6 introduces `{ "data": [...], "nextCursor": "..." }` for every list
endpoint. Applied to these two afterwards, that is a **breaking change to the
top-level type** of an existing response — the most disruptive kind, because no
client survives it.

**Fix:** adopt the envelope now, while these two endpoints are the only ones
that have to change. Doing this in Task 2 rather than Task 6 costs almost
nothing and removes a breaking change from the middle of the milestone.

## 4. `limit` is silently clamped

`GET /v1/tournaments/{id}/standings?limit=1000` returns 100 rows with no
indication that the request was altered:

```java
Math.clamp(limit, 1, MAX_LIMIT)
```

A client asking for 1000 and receiving 100 has no way to distinguish "clamped"
from "only 100 exist" — so it concludes it has the whole leaderboard. Silent
correction of input is the failure mode that produces wrong results rather than
errors.

`limit=0` and `limit=-5` are also silently turned into 1.

**Fix:** reject out-of-range values with `400 INVALID_REQUEST`. Clamping is
defensible for a maximum if it is advertised in a response header; it is not
defensible when the response is indistinguishable from a complete one.

## 5. `POST /v1/auth/request-password-reset` accepts and validates nothing

The handler signature is `Map<String, String>`, so:

- `{}` is accepted and returns `202`
- `{"emial": "..."}` — a typo — is accepted and returns `202`
- any JSON object at all is accepted and returns `202`

It also does nothing: token generation and delivery are not implemented. So it
returns a success status for a request that had no effect and may not even have
named an address.

The always-`202` behaviour is **correct and must be kept** — distinguishing
registered from unregistered addresses would make this an account-enumeration
oracle. The problem is only that a malformed request is also `202`.

**Fix:** a typed `PasswordResetRequest` record with `@Email @NotBlank`, so a
malformed request is `400` while every well-formed one stays `202` regardless
of whether the address exists.

## 6. `MessageResponse` carries nothing machine-readable

`request-password-reset` returns:

```json
{ "message": "If that address has an account, password reset instructions have been sent." }
```

There is nothing here a client can branch on, and the string is display text
that will be reworded or localised. Any client that needs to react has to match
on English prose.

**Fix:** for this endpoint, `202` with an empty body says the same thing more
honestly. More importantly, no new endpoint should adopt this shape — noted in
the contract so the pattern is not copied twenty-five times in M4.

## 7. `emailVerified` is always false

The field exists and is populated from `getEmailVerifiedAt() != null`, but
nothing ever sets that column — email verification is not implemented.

It is documented as always `false` rather than removed, because it is genuinely
reserved and will become meaningful. But a client that gates behaviour on it
today will gate it off forever.

**Fix:** none needed. Keep it documented as inert until verification exists.

---

## Not an inconsistency, but found the same way

Two **cross-tenant defects** were found while trying to describe the
authorization of each endpoint, and were fixed immediately rather than
documented. Recorded in `CHANGELOG.md` under Security and in ADR 0010.

Writing down what an endpoint's authorization *is* turned out to be a better
audit than reading the code for bugs — because the contract forces you to
answer the question for every endpoint, including the ones nobody thought
about.
