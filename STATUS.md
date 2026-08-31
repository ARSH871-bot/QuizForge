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
| M4 | Public API & SDKs | **In progress** | [plan](docs/superpowers/plans/2026-08-18-m4-public-api-and-sdks.md), 10 of 10 tasks; `v0.5.0` not yet cut |
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

- The TypeScript SDK is not published; the npm name is the owner's call ([#83](https://github.com/ARSH871-bot/QuizForge/issues/83))
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
| `tournament` | **full lifecycle** |
| `play` | full |
| `leaderboard` | standings |

**The product is now reachable end to end over HTTP.** A developer can register,
create a workspace, mint a `qf_live_…` key, build and fill a question bank,
schedule a tournament, play it, and read the standings — without touching a
database console. Verified against a running server, not only in tests.

There is now a TypeScript SDK under `packages/sdk-typescript`, with a
quickstart whose transcript is its real output against a running instance.

**But an API key can only read.** Every write endpoint refuses one, so the SDK
consumes the API rather than driving it, and its idempotency-key generation
cannot be exercised by the credential it is built around. Writing the SDK is
what surfaced this: a key is the only credential an API client can hold.
[#98](https://github.com/ARSH871-bot/QuizForge/issues/98) is the decision —
a write is audited against an account, and `audit_event.actor_id` is a foreign
key to `account`, so letting a key write means saying what goes in that column.
Until it is answered, "API-first" is true of reads only.

[`docs/api/README.md`](docs/api/README.md) is the walkthrough, and
[`docs/api/quickstart.sh`](docs/api/quickstart.sh) runs it unattended — both
verified against a database that was empty when they started.

The rendered contract is served locally by `npm run spec`; it is **not hosted**,
because GitHub Pages is unavailable for a private repository on the Free plan.
Its workflow exists and is manual-trigger only ([#83](https://github.com/ARSH871-bot/QuizForge/issues/83)).

All ten M4 tasks are done. What M4 still owes is the `v0.5.0` release — the
first version number that means something to a consumer.

## Known gaps

| # | Gap | Owner | Blocking |
|---|---|---|---|
| [#67](https://github.com/ARSH871-bot/QuizForge/issues/67) | 4 pre-rewrite commits still exist behind `refs/pull/*/head` | **repo owner** | nothing — not publicly readable while private, and the credential in them is revoked |

The exposure in #67 is closed for now by the repository being private:
unauthenticated requests for those SHAs return `404`. The objects have not gone
away, so the issue stays open — it would become readable again the moment the
repository is made public without a Support-side garbage collection first.

Nothing else is open. The mail credential, the unrelated 1.1 MB binary and the
tooling trailers are gone from every ref, verified by cloning the remote fresh
and searching it rather than by inspecting this repository — which looked clean
even when the release tags still pointed at the old history.

## Security controls

**The repository is private, deliberately — to stop others building on this work
— and `main` therefore has no server-side protection.** Not weakened: absent.
Nothing on GitHub's side prevents a direct push to `main`, and nothing forces a
pull request to pass its checks before merging. See
[ADR 0013](docs/adr/0013-the-repository-stays-private.md), which supersedes
ADR 0009.

Rulesets, CodeQL, secret scanning with push protection, and private
vulnerability reporting all require a public repository or paid Advanced
Security. None of them are running.

What is actually enforced:

| Control | Mechanism | Where |
|---|---|---|
| Build, 215 tests, SpotBugs + FindSecBugs | CI job `build` | Actions |
| Secret scanning | gitleaks, job `secret-scan` | Actions |
| Contract lint | Spectral, job `openapi-lint` | Actions |
| Breaking `/v1` changes | oasdiff, job `openapi-breaking` | Actions |
| Changelog and contract currency | job `docs-current` | Actions |
| Dependency alerts and updates | Dependabot | works on private repos |
| Direct pushes to `main` | `.githooks/pre-push` | **local only, advisory** |

Every check still runs on every pull request; what is gone is anything making
them mandatory. The `pre-push` hook is the only thing between a mistake and
`main`, and it is bypassed by `--no-verify` or by any clone that has not run
`git config core.hooksPath .githooks`. It catches accidents, not intent.

**Before ever going public again, in this order:** make it public, then
*immediately* either re-enable CodeQL default setup or remove `CodeQL` from the
dormant ruleset's required checks. The ruleset still exists and resumes on
visibility change, and it requires a check that no longer has a producer — so
every pull request would wait forever on something that is not coming. It could
not be fixed pre-emptively: the ruleset API returns `403` while private.

## Last audit## Last audit## Last audit

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

- **Tests:** 270, all passing
- **Migrations:** V1–V13
- **Modules:** 8 declared, 6 populated (`platform`, `identity`, `content`, `tournament`, `play`, `leaderboard`)
- **ADRs:** 13 (0007 and 0009 superseded by 0013)
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
