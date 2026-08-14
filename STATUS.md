# Status

The single place that says where this product actually stands. Updated in the
same commit as any change that moves a milestone, opens a known gap, or alters
what is safe to do with the code.

**Last updated:** 2026-08-13 (full repository audit)

## Milestones

| | Milestone | State | Evidence |
|---|---|---|---|
| M0 | Foundation & remediation | **Complete** | merged to `main`, CI green |
| M1 | Identity & tenancy | **Complete** | merged to `main`, 56 tests, CI green |
| M2 | Content & authoring | **Complete** | 6 of 6 tasks, 99 tests green |
| M3 | Tournament & play engine | **In progress** | [plan](docs/superpowers/plans/2026-08-12-m3-tournament-and-play-engine.md), 7 tasks; Task 7 is irreversible |
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
| — | 2 commits in history carry a tooling trailer; removed by the same purge as #2 | **repo owner** | nothing |
| — | Legacy `cs.quizzapp` package still serves quiz traffic unauthenticated | M3 | deployment |

## Last audit

**2026-08-13.** Verified rather than assumed: every numeric claim below was
re-measured, every README command executed, and the secret and attribution
sweeps re-run.

Found and fixed: a **CRITICAL** BouncyCastle advisory whose security PR had
been blocked by this repository's own PR-title lint; a Renovate configuration
that was never installed and had therefore updated nothing for three
milestones; six stale merged branches; and five closed issues stuck at
*In Progress* on the board.

## Releases

| Tag | Milestone |
|---|---|
| [v0.3.0](https://github.com/ARSH871-bot/QuizForge/releases/tag/v0.3.0) | M2 — Content and authoring |
| [v0.2.0](https://github.com/ARSH871-bot/QuizForge/releases/tag/v0.2.0) | M1 — Identity and tenancy |
| [v0.1.0](https://github.com/ARSH871-bot/QuizForge/releases/tag/v0.1.0) | M0 — Foundation |

Semantic versioning of the public API begins at M4. `1.0.0` is launch.

## Numbers

- **Tests:** 106, all passing
- **Migrations:** V1–V8
- **Modules:** 8 declared, 4 populated (`platform`, `identity`, `content`, `tournament`)
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
