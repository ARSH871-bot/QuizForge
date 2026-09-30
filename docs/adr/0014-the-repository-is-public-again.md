# 14. The repository is public again

Date: 2026-09-30

## Status

Accepted. Supersedes [ADR 0013](0013-the-repository-stays-private.md).

## Context

ADR 0013 kept the repository private to stop others building on this work, and
accepted the cost: no server-side protection on `main`, and none of GitHub's
native security controls.

It turned out to have a second cost it did not price. The Free plan's 2,000
Actions minutes a month are shared by every private repository on the account.
Once they ran out, this repository's CI stopped with them — every gate, on every
pull request. A public repository's Actions minutes are unlimited.

## Decision

The repository is public.

**Visibility is not a licence.** `LICENSE` is proprietary and unchanged. ADR
0013 already drew this line: being able to read the source grants no right to
use it.

The native controls ADR 0009 adopted are in force again, and have been checked
rather than assumed:

| Control | State |
|---|---|
| Ruleset on `main` | active: pull request required, squash only, branch up to date, no force-push, no deletion |
| Required checks | `build`, `secret-scan`, `docs-current`, `conventional-title`, `CodeQL`, `openapi-lint`, `openapi-breaking` |
| CodeQL | default setup, Java; it found nothing in the backend on its first scan |
| Secret scanning with push protection | enabled |
| Private vulnerability reporting | enabled |

## What happened on the way

ADR 0013 warned that the dormant ruleset would resume on a visibility change
while requiring a `CodeQL` check that no longer had a producer, so every pull
request would wait forever. It happened exactly as written.

The tempting fix was to delete `CodeQL` from the required checks. It was
configured instead, so the requirement now means something: removing a
security gate to get a merge through is the trade this project keeps refusing.

## Consequences

- The owner's reason for ADR 0013 no longer holds: anyone can read the code and
  its history, including the pre-rewrite commits tracked in #67. The credential
  in those is revoked; the exposure is of work, not of secrets.
- `main` is protected server-side again. A change reaches it only through a
  pull request whose required checks pass on a branch current with `main`.
- GitHub Pages is available on a public repository, so the rendered API
  reference can be published (#83).
- **If the repository ever goes private again**, the ruleset goes dormant and
  code scanning stops, and the ordering trap above applies in reverse. Update
  `STATUS.md`, `CONTRIBUTING.md` and `SECURITY.md` in the same change.
