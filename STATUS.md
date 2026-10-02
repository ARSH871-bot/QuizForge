# Status

The single place that says where this product actually stands. Updated in the
same commit as any change that moves a milestone, opens a known gap, or alters
what is safe to do with the code.

**Last updated:** 2026-10-02 (per-question results for organisers)

## Milestones

| | Milestone | State | Evidence |
|---|---|---|---|
| M0 | Foundation & remediation | **Complete** | merged to `main`, CI green |
| M1 | Identity & tenancy | **Complete** | merged to `main`, 56 tests, CI green |
| M2 | Content & authoring | **Complete** | 6 of 6 tasks, 99 tests green |
| M3 | Tournament & play engine | **Complete** | [plan](docs/superpowers/plans/2026-08-12-m3-tournament-and-play-engine.md), 7 of 7; legacy package deleted |
| M4 | Public API & SDKs | **In progress** | [plan](docs/superpowers/plans/2026-08-18-m4-public-api-and-sdks.md), 10 of 10 tasks; `v0.5.0` not yet cut |
| M5 | Web dashboard | **In progress** | `apps/web`: sign up, write a tournament, share the link, watch the board, see how each question went |
| M6 | Player experience & widget | **In progress** | first slice: open a share link, sign up, play against the clock, see your rank |
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
- The web app cannot yet edit or delete a tournament once made (the API can),
  writes three of the five question types, and has no CSV import (M5)
- No billing, observability, backups or runbooks (M7)
- Password reset sends real email over SMTP, but production needs a mail
  provider account; see Known gaps

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

The rendered contract is served locally by `npm run spec`; it is **not hosted**
yet. The repository is public, so GitHub Pages is available: enable it with
"GitHub Actions" as the source and run the manual workflow ([#83](https://github.com/ARSH871-bot/QuizForge/issues/83)).

All ten M4 tasks are done. What M4 still owes is the `v0.5.0` release — the
first version number that means something to a consumer.

## Known gaps

| # | Gap | Owner | Blocking |
|---|---|---|---|
| — | A guest can play again from another browser under a new name. By design: guests are opt-in per tournament, names are unique per workspace, guests are marked on the board, and new guest identities are limited per network address | — | nothing; leave guests off for anything that counts |
| — | The per-address guest limit reads the connection's address. Behind a load balancer every guest shares one address until forwarded headers are trusted, which must be configured for the host rather than switched on blindly | **repo owner** (at deploy) | a classroom behind a proxy hitting the limit at once |
| — | A `PLAYER` sees every tournament in the workspace it joined, not only the one it was invited to | — | nothing today; matters once a workspace runs private tournaments |
| — | Email delivery needs a provider account. Without `SPRING_MAIL_HOST` the API runs, warns at startup, and sends no reset emails | **repo owner** | anyone who forgets a password in production |
| — | Never deployed. `Dockerfile` builds one image serving the API and web app; the database login must be able to bypass row-level security, and the app refuses to start if it cannot | **repo owner** (host account) | anyone outside this machine using it |
| [#67](https://github.com/ARSH871-bot/QuizForge/issues/67) | 4 pre-rewrite commits still exist behind `refs/pull/*/head` | **repo owner** | nothing — publicly readable again, but the credential in them is revoked |

The repository is public again (ADR 0014), so those SHAs are readable to
anyone who has them. The credential they contain is revoked, which is why this
blocks nothing; the issue stays open because only a Support-side garbage
collection removes the objects.

Nothing else is open. The mail credential, the unrelated 1.1 MB binary and the
tooling trailers are gone from every ref, verified by cloning the remote fresh
and searching it rather than by inspecting this repository — which looked clean
even when the release tags still pointed at the old history.

## Security controls

**The repository is public, and `main` is protected server-side.** It went
private for a while (ADR 0013) and came back, because private repositories on
the Free plan share 2,000 Actions minutes a month and CI stopped when they ran
out. See [ADR 0014](docs/adr/0014-the-repository-is-public-again.md). Public
source is not a licence to use it: `LICENSE` is proprietary.

| Control | Mechanism | Where |
|---|---|---|
| Pull request required, squash only, branch up to date, no force-push | ruleset `main protection` | GitHub |
| Build, 312 tests, SpotBugs + FindSecBugs | CI job `build`, required | Actions |
| Static analysis | CodeQL default setup, Java; required | GitHub |
| Secret scanning | gitleaks (`secret-scan`, required) and GitHub push protection | Actions, GitHub |
| Contract lint and breaking changes | `openapi-lint`, `openapi-breaking` (oasdiff pinned by digest), required | Actions |
| Changelog and contract currency | `docs-current`, required | Actions |
| SDK and web app build against the contract | `sdk-typescript`, `web`, required | Actions |
| Vulnerability reports | private vulnerability reporting | GitHub |
| Dependency alerts and updates | Dependabot | GitHub |

If the repository ever goes private again, the ruleset goes dormant and code
scanning stops. Update this section, `CONTRIBUTING.md` and `SECURITY.md` in the
same change.

## Last audit

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

- **Tests:** 312, all passing
- **Migrations:** V1–V18
- **Modules:** 9 declared, 7 populated (`platform`, `identity`, `content`, `tournament`, `play`, `leaderboard`, `notify`); `api` holds the generated contract and `billing` is empty until M7
- **ADRs:** 14 (0007, 0009 and 0013 superseded)
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
