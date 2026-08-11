# 3. Attempt is the aggregate root for play

Date: 2026-08-11

## Status

Accepted

## Context

The prototype modelled play as a `Participation` row plus a `Score` number,
with the answers themselves persisted nowhere. This produced at least six
defects: no resumability, unlimited resubmission, a score denominator that
drifted from what the player actually saw, an orphaned paginated flow that
recorded nothing, correct answers leaking to the client, and no enforceable
time limit.

## Decision

Introduce `Attempt` as a stateful aggregate root
(`STARTED -> SUBMITTED -> GRADED -> EXPIRED`) owning a collection of
`Response` entities. The question set is frozen into the attempt at creation.
Grading happens server-side.

Attempt policy is configurable per tournament: `maxAttempts` (default 1) and
`scoringPolicy` (`BEST | FIRST | LAST | AVERAGE`, default `FIRST`).

## Consequences

Positive: all six defects are resolved structurally rather than individually.
Resumability, idempotent submission, and anti-cheat timing become natural
properties of the model.

Negative: more write traffic — one row per answered question rather than one
per completed quiz. Acceptable, and it is what makes per-question analytics
possible later.
