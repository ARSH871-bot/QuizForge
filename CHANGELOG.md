# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file is updated in the same commit as the change it describes. A change
that is not worth a line here is not worth shipping. `[Unreleased]` is rolled
into a version section when a release is tagged — see
[CONTRIBUTING.md](CONTRIBUTING.md#cutting-a-release).

Semantic versioning of the **public API** begins at 0.5.0 (M4). Until then
these are milestone markers, and the minor number tracks the milestone.

## [Unreleased]

### Security

- **Two cross-tenant defects closed.** With the `X-QuizForge-Workspace` header
  simply omitted, an authenticated account could read another workspace's
  standings (`200` with their rows) and start an attempt on another workspace's
  tournament (`201` — a write). Tenant isolation depends on Row-Level Security,
  and RLS only engages once a workspace is in scope; without one the connection
  ran unfiltered. Both were confirmed by test before being fixed.
- **`WorkspaceScopeFilter`** now refuses any authenticated `/v1` request with no
  workspace in scope, before it reaches a handler. Isolation no longer depends
  on each controller remembering to check — it was correct in three of five
  places, and M4 adds roughly twenty-five more. ADR 0010.

### Added

- **`openapi.yaml`** — the public API contract, describing all 13 `/v1`
  endpoints exactly as they behave today, with `Problem` modelled once and every
  `ErrorCode` documented as an enum with its HTTP status. Verified against a
  running instance rather than written from the source alone.
- **Spectral linting** (`.spectral.yaml`, CI job `openapi-lint`), failing on
  hints as well as errors. Verified by breaking the spec and watching the job
  reject it. The OWASP ruleset is deliberately deferred to Task 8, because
  several of its rules require `RateLimit-*` headers that do not exist yet and
  adopting it now would mean suppressing them.
- **Contract drift guard** in `docs-current`: a change under a module's `web`
  package without a change to `openapi.yaml` fails the build.
- `docs/api/known-inconsistencies.md` — seven shapes that are wrong but
  documented as-is, with the reasoning for fixing them in Task 2 while nothing
  consumes the contract and the change is still free.

- M4 (public API and SDKs) implementation plan: `openapi.yaml` as the source of
  truth with server interfaces generated from it, the CRUD surfaces M1–M3 never
  built, idempotency, cursor pagination, per-key rate limiting, and a TypeScript
  SDK. Webhooks, sandbox mode and the Python/Go SDKs are deferred with reasons
  recorded, rather than dropped silently.

### Fixed

- The `0.4.0` heading carried the date the work was done rather than the date
  the release was tagged.

## [0.4.0] — 2026-08-18

Milestone M3: tournaments and the play engine. Also the milestone in which the
repository became public and stopped substituting for the security controls it
could not have while private.

### Added

- **Tournaments (M3).** A scheduled run of questions drawn from a bank, open
  for a window. State (SCHEDULED/OPEN/CLOSED) is derived from the clock rather
  than stored, so no scheduled job transitions it and a row can never disagree
  with the calendar. A tournament asking for more questions than its bank holds
  is rejected at creation, not discovered by the first player.
- **The `Attempt` aggregate (M3).** One player's run at a tournament, as
  durable state rather than a marker row. Replaces the prototype's
  disconnected `Participation` and `Score`, fixing six defects at once:
  resumability after a disconnect, idempotent submission, a score denominator
  frozen at creation so a player is never graded against questions they did
  not see, per-question responses that the paginated flow can persist,
  server-side grading, and a time limit computed from the server clock rather
  than accepted from a request.
- **Playing an attempt (M3).** Start, per-question retrieval, answer and
  submit. Answers are graded and persisted as the player advances, so an
  attempt survives a disconnect and submission merely aggregates stored
  outcomes. Options are shuffled per attempt and the order stored, so a
  refresh does not reshuffle under the player while two players still get
  different orders.
- **Play endpoints (M3).** `POST /v1/tournaments/{id}/attempts`,
  `GET|POST /v1/attempts/{id}/questions/{n}`, `POST /v1/attempts/{id}/submit`,
  `GET /v1/tournaments/{id}/standings`, and a tournament listing. All
  authenticated and workspace-scoped; another account's attempt returns 404
  rather than 403.
- **Expiry sweep (M3).** Closes attempts abandoned past their time limit so
  leaderboards settle, coordinated across instances by a Postgres advisory
  lock. The sweep is *not* what enforces the limit — that is checked on every
  access — so a player never benefits from the job running late.
- **Leaderboards (M3).** Standings materialised when an attempt closes, with
  the tournament's scoring policy (BEST/FIRST/LAST/AVERAGE) applied at read
  time so changing a policy takes effect without recomputing history. Ties
  break in favour of whoever finished first.
- **`QuestionAccess`**, the content module's published API, so `tournament`
  (and later `play`) can ask about questions without reaching into
  `content.app` or `content.domain`.
- **`TournamentAccess`**, the tournament module's published API, so `play` can
  read a tournament without reaching into its internals.
- **`PlayAccess` and the `AttemptGraded` event**, so leaderboards react to play
  without either module reaching into the other.
- M3 (tournament and play engine) implementation plan, including the retirement
  of the legacy package.
- `LICENSE` (proprietary), `CODE_OF_CONDUCT.md`, `SUPPORT.md`, and README
  status badges — completing GitHub's community standards checklist.
- Milestone tags and GitHub Releases: `v0.1.0` (M0), `v0.2.0` (M1), `v0.3.0`
  (M2). Three milestones had shipped with no tags at all, so there were no
  restore points and no visible version history.
- ADR 0009, recording the move to public and what replaced each substituted
  control.

### Changed

- Dependencies brought current: Spring Boot 3.5.16, Spring Modulith 1.4.12,
  gitleaks-action 3, setup-java 5, checkout 7, junit-report 6, ArchUnit,
  maven-wrapper, BouncyCastle, SpotBugs, FindSecBugs.
- Spring Boot 3.5.16 required Spring Modulith 1.4.12 alongside it: Modulith
  1.4.3's annotation processor fails at compile with
  `NoClassDefFoundError: JsonWriter$Extractor` against the newer Boot. Bumping
  Boot alone breaks the build, which is why Dependabot's grouped PR failed.
- Dependabot now ignores Spring Boot **major** versions. 4.x moves to Spring
  Framework 7 with breaking changes across Security and Data and drops
  testcontainers from its managed dependencies — a migration to schedule
  deliberately, not to merge from a green bot PR.
- `.githooks/pre-push` is no longer the branch protection, only a fast local
  failure ahead of it. Its comments said server-side protection was impossible;
  that is no longer true and would have misled the next reader.

### Removed

- **The legacy `cs.quizzapp` package and all 41 `/api/**` endpoints.** This was
  the last unauthenticated surface in the product. V11 drops the coursework
  schema (`quiz`, `score`, `participation`, `users` and friends) — confirmed by
  the owner as holding no data worth keeping.
- Hand-declared component, entity and repository scanning on the entry point,
  and the bean-name qualifiers on `QuestionService` and `AuthController`. All
  existed only to coexist with the legacy package.
- The `codeql` job in `ci.yml`. CodeQL default setup and an in-repository
  CodeQL workflow cannot coexist — GitHub refuses results uploaded from an
  advanced configuration while default setup is enabled — so keeping both would
  have meant a permanently failing check rather than a second opinion.

### Security

- **The repository is public, and every native GitHub control is on**: a
  ruleset protecting `main` server-side, secret scanning with push protection,
  CodeQL default setup on the extended query suite, private vulnerability
  reporting, and Dependabot. ADR 0009 supersedes ADR 0007; gitleaks and
  SpotBugs are kept alongside the native controls rather than removed, for
  reasons recorded there. The ruleset was verified by attempting a direct push
  to `main` and confirming `GH013`, not by trusting its configuration.
- **History rewritten.** A mail credential present since the initial commit, a
  1.1 MB unrelated binary, and every authoring-tool trailer are gone from all
  refs. The credential was revoked at the provider first. Verified by cloning
  the remote fresh and searching it, not by inspecting the local repository —
  which stayed clean even while the three release tags still pointed at
  pre-rewrite commits. A mirror backup was taken before the rewrite.
- FindSecBugs 1.13.0 → 1.14.0, which flagged `XSS_SERVLET` in the
  authentication entry point. A false positive — the only interpolated value is
  a compile-time constant — but the hand-written JSON it pointed at is now
  serialised by Jackson, which removes the smell rather than suppressing the
  finding.
- Bumped `bcprov-jdk18on` 1.78.1 → 1.84, closing a CRITICAL and a MEDIUM
  advisory. Actual exposure was nil (BouncyCastle is used only as the Argon2
  provider; the flaws are in GOST ciphers and LDAP handling) but the security
  PR had been blocked by this repository's own PR-title lint.
- Replaced the never-installed Renovate configuration with Dependabot, which
  runs without an app install and now emits Conventional Commit titles.

## [0.3.0] — 2026-08-13

Milestone M2: content and authoring.

### Added

- **Question banks and immutable authoring (M2).** Authoring writes version 1;
  revising inserts a new row sharing the lineage and supersedes the previous
  one, leaving it byte-identical so a tournament can pin exactly what a player
  saw. Duplicates are rejected per bank by normalised content hash.
- **Question types (M2).** Five types — SINGLE_CHOICE, MULTI_CHOICE,
  TRUE_FALSE, NUMERIC, SHORT_TEXT — with immutable payload records that
  validate on write, since a JSONB column cannot enforce shape itself.
- **Content schema (M2).** `question_bank` and `question` tables, with
  lineage/version uniqueness, per-bank content-hash de-duplication, and a
  partial index on the current version of each lineage.
- **Server-side grading (M2).** A grader per question type behind a registry.
  Multi-choice requires set equality; numeric compares within absolute
  tolerance; short text normalises without fuzzy matching. A null or
  unparseable answer is incorrect, never an error.
- **CSV import (M2).** Partial success is the normal case: one malformed row
  does not discard the rest, failures are reported with the line number a human
  sees in a spreadsheet, and re-importing the same file is a no-op rather than
  an error.
- **OpenTDB import (M2).** Replaces the legacy `OpenTDBService`. Requests
  base64 rather than URL encoding (the legacy path double-decoded any answer
  containing a percent sign), fails fast on rate limiting instead of sleeping
  six seconds per category, and de-duplicates through the same content hash as
  every other import.
- **Tenant isolation for content.** RLS policies on `question_bank` and
  `question`, proven by a test that asks for another workspace's rows and gets
  nothing.
- **Runtime enforcement of Row-Level Security.** Closes the gap `0.2.0` shipped
  with. Every transaction carrying a tenant now assumes the `NOBYPASSRLS` role
  `quizforge_app` and sets `app.workspace_id`, both transaction-locally. Proven
  by a test asserting a workspace cannot read another's rows even when
  explicitly asking for them.
- **`WorkspaceAccess`**, identity's published authorization API. Other modules
  ask permission questions through it rather than reaching into
  `identity.app` or `identity.domain`, so the permission matrix stays internal.
- M2 (content and authoring) implementation plan: immutable versioned
  questions, five question types with server-side grading, and a CSV/OpenTDB
  import pipeline.

### Changed

- The legacy `Question` entity, `QuestionRepository` and `question` table are
  all renamed with a `Legacy` prefix, freeing those names for the content
  module. Spring, Spring Data and Hibernate each derive a distinct identifier
  from the simple class name, and all three collided.
- The legacy `question` table is renamed to `legacy_question`, freeing the name
  for the new content model. Its entity mapping gains an explicit
  `@CollectionTable` so Hibernate still finds `question_options`. The legacy
  table is deleted outright in M3.

## [0.2.0] — 2026-08-12

Milestone M1: identity and tenancy.

### Added

- **Identity and tenancy (M1).** Accounts with Argon2id hashing at OWASP
  parameters, workspaces with an OWNER/ADMIN/EDITOR/VIEWER permission matrix,
  hashed sessions and API keys, an append-only audit log, and Postgres
  Row-Level Security policies.
- `POST /v1/auth/register`, `/login`, `/logout`; `GET /v1/auth/me`;
  `POST /v1/auth/request-password-reset`.
- Typed prefixed identifiers (`acc_…`, `wsp_…`) backed by UUIDv7, rendered only
  at the API boundary.
- RFC 9457 Problem Details for every error, with stable machine-readable codes.
- SpotBugs with FindSecBugs, replacing CodeQL on a private repository. The bar
  was proven rather than assumed: a deliberately vulnerable canary class was
  added, confirmed to produce `SQL_INJECTION_JDBC` and `PREDICTABLE_RANDOM`
  findings, and removed.
- Mailpit in the local stack, so email paths can be developed with no account.
- `STATUS.md` and `CHANGELOG.md`, plus a "Definition of done for any change"
  in `CONTRIBUTING.md` and a `docs-current` CI job that fails a pull request
  touching application source without updating the changelog, and blocks edits
  to already-applied Flyway migrations.
- ADRs 0007 and 0008, bringing the total to eight.

### Changed

- `/v1/**` now requires authentication. `/api/**` (legacy) remains open until
  M3 retires it.
- CSRF protection enabled for cookie-authenticated requests, exempting
  bearer-token calls and the pre-session auth path.
- Passwords: minimum length raised from 6 to 12, per NIST SP 800-63B.
- Email is optional and disabled by default; the application starts with no
  mail configuration at all. Previously `MAIL_USERNAME` and `MAIL_PASSWORD`
  had no defaults, so a missing mail account blocked startup entirely.

### Security

- Legacy BCrypt password encoder replaced with Argon2id. Existing BCrypt
  hashes remain verifiable, so accounts upgrade on next login.
- Session and API key material stored only as SHA-256 digests.
- Authentication timing equalised: an unknown email costs the same as a wrong
  password.
- Password reset no longer returns the token in the HTTP response. A
  regression test enforces this.

### Known issues

- **Row-Level Security is not enforced at runtime.** The policies exist and are
  proven, but the application connects as a superuser, for which Postgres
  silently skips RLS. Fixed in `0.3.0`; recorded here because this release
  shipped with it.

## [0.1.0] — 2026-08-12

Milestone M0: foundation and remediation of the inherited coursework project.

> This file did not exist at `0.1.0` — it was created during M1, which is how
> the whole history came to sit under `[Unreleased]`. The entries below were
> reconstructed by checking each claim against the `v0.1.0` tree rather than
> inferred from wording, so several items previously grouped with M0
> (SpotBugs, Mailpit, `STATUS.md`, the `docs-current` job) are listed under
> `0.2.0`, where they actually shipped.

### Added

- **Foundation (M0).** Monorepo layout, Flyway-owned schema on PostgreSQL,
  Testcontainers harness, Spring Modulith module skeleton with ArchUnit
  guardrails, CI with build, gitleaks and PR-title linting.
- ADRs 0001–0006 in `docs/adr/`.

### Changed

- Java 17 → 21, Spring Boot 3.3.5 → 3.5.6.

### Removed

- `spring.jpa.hibernate.ddl-auto=update`. Flyway owns the schema; Hibernate
  validates only.
- MySQL driver and dialect configuration. PostgreSQL only.
- Hardcoded default admin and player credentials from application startup.
- Committed mail credentials from the working tree. Removing them from the
  history took until `0.4.0`.
- Tracked build artifacts (`target/`) and IDE configuration (`.idea/`).

[Unreleased]: https://github.com/ARSH871-bot/QuizForge/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/ARSH871-bot/QuizForge/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/ARSH871-bot/QuizForge/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ARSH871-bot/QuizForge/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/ARSH871-bot/QuizForge/releases/tag/v0.1.0
