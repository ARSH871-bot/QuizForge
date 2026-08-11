# Status

The single place that says where this product actually stands. Updated in the
same commit as any change that moves a milestone, opens a known gap, or alters
what is safe to do with the code.

**Last updated:** 2026-08-12

## Milestones

| | Milestone | State | Evidence |
|---|---|---|---|
| M0 | Foundation & remediation | **Complete** | merged to `main`, CI green |
| M1 | Identity & tenancy | **Complete** | merged to `main`, 56 tests, CI green |
| M2 | Content & authoring | Not started | no plan written |
| M3 | Tournament & play engine | Not started | no plan written |
| M4 | Public API & SDKs | Not started | no plan written |
| M5 | Web dashboard | Not started | no plan written |
| M6 | Player experience & widget | Not started | no plan written |
| M7 | Commercial & launch readiness | Not started | no plan written |

Issues exist only for milestones whose plan is written. Inventing task detail
ahead of a plan produces fiction, not tracking.

## What is safe to do with this code today

**Safe:** run it locally, develop against it, run the test suite.

**Not safe:** deploy it anywhere reachable. One reason remains:

1. The legacy `/api/**` endpoints are still completely unauthenticated. They
   are retired in M3. Until then, anyone reachable can read and modify quiz
   data without credentials.

Tenant isolation is now enforced by PostgreSQL itself (#23, ADR 0008), so a
missing `WHERE workspace_id = ?` returns nothing rather than another
customer's data.

## Known gaps

| # | Gap | Owner | Blocking |
|---|---|---|---|
| [#2](https://github.com/ARSH871-bot/QuizForge/issues/2) | Mail credential in git history; referenced by no code | **repo owner** | nothing |
| — | Branch protection is advisory only (private repo, Free plan) | accepted | see ADR 0007 |
| — | Legacy `cs.quizzapp` package still serves quiz traffic unauthenticated | M3 | deployment |

## Numbers

- **Tests:** 56, all passing
- **Migrations:** V1–V5
- **Modules:** 8 declared, 2 populated (`platform`, `identity`)
- **ADRs:** 8
- **Monthly cost:** $0

## Where to look

| Question | File |
|---|---|
| Why is it built this way? | `docs/adr/` |
| What is the overall design? | `docs/superpowers/specs/` |
| What are the steps for the current milestone? | `docs/superpowers/plans/` |
| What changed, and when? | `CHANGELOG.md` |
| How do I run it? | `README.md` |
| How do I contribute a change? | `CONTRIBUTING.md` |
