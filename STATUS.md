# Status

The single place that says where this product actually stands. Updated in the
same commit as any change that moves a milestone, opens a known gap, or alters
what is safe to do with the code.

**Last updated:** 2026-08-12

## Milestones

| | Milestone | State | Evidence |
|---|---|---|---|
| M0 | Foundation & remediation | **Complete** | merged to `main`, CI green |
| M1 | Identity & tenancy | **Complete** | [PR #22](https://github.com/ARSH871-bot/QuizForge/pull/22), 54 tests, CI green |
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

**Not safe:** deploy it anywhere reachable. Two reasons, both deliberate and
tracked:

1. The legacy `/api/**` endpoints are still completely unauthenticated. They
   are retired in M3.
2. Row-Level Security is not enforced at runtime (#23). Tenant isolation
   currently rests on application-layer checks alone.

## Known gaps

| # | Gap | Owner | Blocking |
|---|---|---|---|
| [#23](https://github.com/ARSH871-bot/QuizForge/issues/23) | RLS policies exist but are inert at runtime — the app connects as a superuser | dev | trusting tenant isolation |
| [#2](https://github.com/ARSH871-bot/QuizForge/issues/2) | Mail credential in git history; referenced by no code | **repo owner** | nothing |
| — | Branch protection is advisory only (private repo, Free plan) | accepted | see ADR 0007 |
| — | Legacy `cs.quizzapp` package still serves quiz traffic unauthenticated | M3 | deployment |

## Numbers

- **Tests:** 54, all passing
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
