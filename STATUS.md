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
| M3 | Tournament & play engine | **Complete** | [plan](docs/superpowers/plans/2026-08-12-m3-tournament-and-play-engine.md), 7 of 7; legacy package deleted |
| M4 | Public API & SDKs | **In progress** | [plan](docs/superpowers/plans/2026-08-18-m4-public-api-and-sdks.md), 4 of 10 tasks |
| M5 | Web dashboard | Not started | no plan written |
| M6 | Player experience & widget | Not started | no plan written |
| M7 | Commercial & launch readiness | Not started | no plan written |

Issues exist only for milestones whose plan is written. Inventing task detail
ahead of a plan produces fiction, not tracking.

## What is safe to do with this code today

**Safe:** run it locally, develop against it, run the test suite.

**Deployable, with the caveats below.** The legacy unauthenticated surface is
gone: every `/api/**` route now returns 401, verified against a running
instance. Every endpoint requires credentials except `/v1/auth/**` and
`/actuator/health`.

Remaining caveats before a real deployment — none of them security holes, all
of them missing polish:

- No public API contract or SDKs yet (M4)
- No web interface (M5)
- No billing, observability, backups or runbooks (M7)
- Email is disabled by default; a provider is wired in M4

Tenant isolation is now enforced by PostgreSQL itself (#23, ADR 0008), so a
missing `WHERE workspace_id = ?` returns nothing rather than another
customer's data.

## The API-first gap M4 closes

Worth stating explicitly, because "M3 complete" reads better than the situation
warrants. Services exist for everything; HTTP surfaces do not:

| Module | Reachable over HTTP today |
|---|---|
| `identity` | auth, workspaces, members, API keys |
| `content` | **banks, questions, CSV and OpenTDB imports** |
| `tournament` | list only (creation in M4 Task 5) |
| `play` | full |
| `leaderboard` | standings |

A developer can register, create a workspace, mint a `qf_live_…` key, build a
question bank and fill it — entirely over HTTP. Verified against a running
server, not only in tests.

**One gap remains: tournaments can be listed but not created.** So a bank can be
stocked and then not played, which Task 5 closes. Nothing is broken; the engine
works and is tested. The surfaces were never built, because M1–M3 built downward
through the domain rather than outward through HTTP.

## Known gaps

| # | Gap | Owner | Blocking |
|---|---|---|---|
| [#67](https://github.com/ARSH871-bot/QuizForge/issues/67) | GitHub still serves 4 pre-rewrite commits by SHA through `refs/pull/*/head` | **repo owner** | nothing — the credential in them is revoked, so it is dead text |

Nothing else is open. The mail credential, the unrelated 1.1 MB binary and the
tooling trailers are gone from every ref, verified by cloning the remote fresh
and searching it rather than by inspecting this repository — which looked clean
even when the release tags still pointed at the old history.

## Security controls

**The repository is private again, and most of these are consequently
inactive.** Recorded rather than quietly left stale, because the section below
described them as working.

GitHub events show it was public on 2026-08-18 and is private now; the last
CodeQL run was 2026-08-20. Nothing in this repository made that change, and it
is a legitimate decision to make — but it silently reverses ADR 0009, and the
`STATUS.md` that claimed otherwise was the kind of document that gets believed.

| Control | Status now | Why |
|---|---|---|
| Ruleset on `main` | **inactive** | rulesets return 403 on a private Free-plan repository |
| CodeQL | **off** | code scanning needs a public repository or paid Advanced Security |
| Secret scanning + push protection | **off** | same |
| Private vulnerability reporting | **off** | same |
| Dependabot alerts | active | works on private repositories |
| CI (`build`, `openapi-lint`, `openapi-breaking`, `secret-scan` via gitleaks, `docs-current`) | active | plain Actions, unaffected by visibility |
| `pre-push` hook | active locally | advisory only, and bypassed by `--no-verify` |

**The practical consequence: `main` is unprotected.** Pull request #88 merged
while `CodeQL` was a required check that never reported — a required check whose
producer is disabled does not block a merge, it simply never appears. That is
worth knowing independently of this repository: *requiring* a check is not the
same as *having* one.

Everything CI enforces still enforces. What is gone is the server-side layer:
nothing now prevents a direct push to `main`, and no scanning runs on push.

Two ways forward, both fine, but they should be chosen rather than drifted into:

1. **Public again** — every control resumes, including the stored ruleset, at no
   cost beyond the code being readable.
2. **Stay private** — then ADR 0009 should be superseded in turn, and the
   substitutes ADR 0007 described (the `pre-push` hook, gitleaks, SpotBugs)
   become the whole story again. They still run; they were never removed.

Tracked in [#91](https://github.com/ARSH871-bot/QuizForge/issues/91).

## Last audit## Last audit

**2026-08-17.** The repository was made public after purging its history, and
every security control it had been substituting for was replaced with the
native one. Two things were caught by verifying instead of assuming: the purge
had left the three release tags pointing at pre-rewrite commits, so a fresh
clone still contained the credential; and an in-repository CodeQL job would have
turned permanently red against CodeQL default setup, which refuses results from
an advanced configuration.

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
| [v0.4.0](https://github.com/ARSH871-bot/QuizForge/releases/tag/v0.4.0) | M3 — Tournaments and play |
| [v0.3.0](https://github.com/ARSH871-bot/QuizForge/releases/tag/v0.3.0) | M2 — Content and authoring |
| [v0.2.0](https://github.com/ARSH871-bot/QuizForge/releases/tag/v0.2.0) | M1 — Identity and tenancy |
| [v0.1.0](https://github.com/ARSH871-bot/QuizForge/releases/tag/v0.1.0) | M0 — Foundation |

Semantic versioning of the public API begins at M4 (`0.5.0`). `1.0.0` is launch.

## Numbers

- **Tests:** 215, all passing
- **Migrations:** V1–V11
- **Modules:** 8 declared, 6 populated (`platform`, `identity`, `content`, `tournament`, `play`, `leaderboard`)
- **ADRs:** 12 (0007 superseded by 0009)
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
