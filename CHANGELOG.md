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

### Added

- **Per-credential rate limiting (M4).** A token bucket in Postgres, one per
  API key or session account, with `RateLimit-Limit`, `RateLimit-Remaining` and
  `RateLimit-Reset` on **every** `/v1` response rather than only on `429` — a
  client told its budget once it has run out has been told too late.

  Over the limit is `429 RATE_LIMITED` with `Retry-After`. A bucket rather than
  a fixed window, because a window lets a caller spend its whole allowance in
  the last second of one window and again in the first second of the next.

  Limits are per **credential**, so two keys in one workspace do not share an
  allowance, and configurable per workspace via `workspace.rate_limit_per_minute`
  — M7's billing tiers change a row rather than shipping a deploy.

  `/v1/auth/**` is not limited: those requests have no workspace to scope a
  bucket to, and repeated login failures already lock the account.

  V13 adds `rate_limit_bucket` with RLS.
- **The OWASP API Security ruleset**, deferred in Task 1 until the rate limiting
  it checks for existed. It caught 66 real findings — `RateLimit-*` declared
  only on `429` — plus an undeclared server audience and three strings typed as
  free text that are in fact constrained. All fixed.
- A pinned Node toolchain (`package.json`) for contract linting, replacing the
  ad-hoc `npx` invocation. An unpinned linter changes the meaning of a green
  build without anyone editing anything.

- **Idempotency keys (M4).** Every mutating `/v1` request accepts an
  `Idempotency-Key`, so a retry after a timeout cannot do the thing twice. A
  repeat with the same key and the same request replays the original response,
  marked `Idempotency-Replayed: true`, without running the handler.

  The key is claimed by **inserting** a row before the request runs, in its own
  committed transaction. A concurrent retry hits the primary key, fails to
  insert, and finds the claim — the database decides the race, which is the only
  participant that can decide it correctly. A check-then-insert would leave a
  window in which both callers see nothing.

  The same key with a **different** request is `422 IDEMPOTENCY_KEY_REUSED`:
  almost always a key that was reused rather than regenerated, and replaying the
  earlier response would be a correct answer to a different question. The hash
  covers method and path as well as body.

  Server errors release the claim rather than recording it. A `500` says nothing
  about whether a retry would fail too, and replaying one for 24 hours would
  turn a bad minute into a bad day.

  V12 adds `idempotency_key`, keyed on `(workspace_id, key)` with RLS, plus a
  purge that drops rows past the 24-hour window — coordinated by an advisory
  lock, like the attempt sweep.

- **Cursor pagination (M4).** Every list endpoint now takes `limit` and
  `cursor` and fills in `nextCursor`. Pagination is **keyset**, never offset: a
  cursor names the last row seen, so a row inserted while a client pages through
  cannot duplicate or hide another one.

  The key is the primary key. Every identifier here is a UUIDv7, which embeds
  its creation time and sorts chronologically in the byte order PostgreSQL
  compares `uuid` values in — so ordering by key *is* ordering by creation time,
  with no ties and no tuple comparison.

  A malformed or unrecognised cursor is `400 INVALID_CURSOR`, never a 500 and
  never silently treated as "start again" — which would restart a client's
  pagination rather than telling it something is wrong.

  `GET /v1/tournaments/{id}/standings` is the one exception and says so: it is
  ranked in application code at read time, so there is no key to page on. It
  returns a bounded top-N instead.

- **Tournament lifecycle endpoints (M4).** `POST /v1/tournaments`,
  `GET|PATCH|DELETE /v1/tournaments/{id}`, and
  `GET /v1/tournaments/{id}/attempts-summary` — the organiser's view of who has
  played, including attempts in progress that appear on no leaderboard.

  Amending and deleting are refused once a tournament is `OPEN` or `CLOSED`: its
  players started under the current rules, and a closed one has results those
  rules explain. Deleting is a real delete rather than another tombstone,
  because a scheduled tournament with no attempts is only a plan.

  `TournamentSummary` gains `bankId` and `scoringPolicy`, both optional so the
  addition stays additive.

### Fixed

- The platform design and the M4 plan both cited **RFC 9331** for the
  `RateLimit-*` headers. That RFC is the L4S congestion notification protocol
  and has nothing to do with HTTP rate limits; the headers come from
  `draft-ietf-httpapi-ratelimit-headers`. Both citations corrected.
- `ci.yml` still described the repository as public in explaining why there is
  no CodeQL job. It has been private since ADR 0013.

### Changed

- `ImportReport`, `TournamentDraft` and every other date-time field lost the
  `maxLength` that the generator was turning into `@Size` on an
  `OffsetDateTime` — harmless on a response, a `500` on any request body that
  carried one.
- **The repository stays private**, deliberately: to stop others building on
  this work. ADR 0013 supersedes ADR 0009 and puts ADR 0007's substitutes back
  in force — they were never removed. `main` therefore has **no server-side
  protection**, and `STATUS.md` now says so plainly rather than describing
  controls that are switched off.
- `.githooks/pre-push` no longer claims a ruleset will reject a bypassed push.
  It is now the only thing guarding `main`, and a hook that overstates its own
  authority is worse than no hook.

### Fixed

- `STATUS.md` described the server-side security controls as active. The
  repository is private again, so the ruleset, CodeQL, secret scanning and
  private vulnerability reporting are all inactive — rulesets and code scanning
  need a public repository or paid Advanced Security. Corrected rather than left
  stale, and tracked in
  [#91](https://github.com/ARSH871-bot/QuizForge/issues/91).

  Surfaced when #88 merged while `CodeQL` was a required check that never
  reported: a required check whose producer is disabled does not block a merge,
  it simply never appears.

### Added

- **Question bank, question and import endpoints (M4).** The `content` module
  had no `web` package at all: its services have been complete since M2 and
  unreachable ever since, so a workspace could be created but never filled.

  `POST|GET /v1/question-banks`, `GET|DELETE /v1/question-banks/{bankId}`,
  `GET|POST /v1/question-banks/{bankId}/questions`,
  `GET|PATCH|DELETE /v1/questions/{questionId}`,
  `GET /v1/questions/{questionId}/versions`, and CSV and OpenTDB imports.

  `PATCH` honours M2's immutability rule: it **inserts a new version** sharing
  the previous one's `lineageId` and leaves the previous row byte-identical
  apart from its supersession pointer, so a tournament still pins exactly what a
  player saw. Verified by comparing the whole rendered document before and after
  a revision, not a few sampled fields.

  Payloads are a **discriminated union** — three shapes across five question
  types. A TypeScript client narrows exhaustively on `kind`; proven by
  type-checking a `switch` with a `never` fallthrough under `tsc --strict`.

- **Workspace, member and API key endpoints (M4).** `POST|GET /v1/workspaces`,
  `GET|PATCH /v1/workspaces/current`, `GET|POST /v1/members`,
  `PATCH|DELETE /v1/members/{accountId}`, `GET|POST /v1/api-keys` and
  `DELETE /v1/api-keys/{keyId}`.

  This closes the credential-issuing gap: `ApiKeyService` has been able to mint
  keys since M1 and nothing exposed it, so an API-first product had no way to
  issue the credential its primary authentication mechanism depends on. A
  developer can now go from registration to a working `qf_live_…` key entirely
  over HTTP.

  A key's secret appears in exactly one response, when it is created. Only a
  SHA-256 digest is stored, so no endpoint can return it again. Revocation takes
  effect on the next request — keys are resolved against the database every
  time, so there is no cache to expire.

  Workspace-scoped paths carry no workspace id: `/v1/members` and `/v1/api-keys`
  act on the workspace already in scope. Two ways to name a tenant would mean
  reconciling them when they disagree, which is how cross-tenant defects get
  written.

### Changed

- **Import reports carry the failed line as a field**, not inside the message.
  It used to be prose — `"row 3: ..."` — so any client wanting to point a user
  at the offending row had to parse an English sentence that was free to be
  reworded or localised. `ImportReport.messages` becomes
  `failures: [{ line, message }]`; the line is `null` for OpenTDB, which has no
  lines.
- `QuestionBankService.create` now stores the description it is given. The
  column and setter have existed since M2 and nothing called the setter, so the
  API accepted a description, answered `201`, and discarded it.
- **Controllers implement interfaces generated from `openapi.yaml`.** A
  controller whose path, verb, parameters, status or response type disagrees
  with the published contract no longer compiles. `interfaceOnly=true` — the
  generator produces the interface and the models, controllers keep their own
  bodies. ADR 0011.
- **Six contract shapes corrected**, while nothing consumed the contract and
  the changes were still free. `oasdiff` scores them as seven breaking changes,
  which is the argument for having made them on the day they were found rather
  than after an SDK shipped:
  - `attemptId`, `questionId` and `accountId` are now prefixed identifiers, not
    bare UUIDs. Previously `POST .../attempts` returned `att_…` and
    `GET /v1/attempts/{id}` returned a bare UUID for the same attempt, so a
    client could not use the value it had just been handed.
  - List endpoints return `{data, nextCursor}`. Adopted before cursor
    pagination exists, because adding it afterwards would have changed the
    top-level type of a live endpoint.
  - `limit` outside 1–100 is rejected rather than silently clamped.
  - `POST /v1/auth/request-password-reset` takes a typed, validated body and
    returns an empty `202`. It still cannot be used to discover which addresses
    are registered.
  - Naming a workspace you are not a member of is `403 PERMISSION_DENIED`, not
    `401`. Re-authenticating could never have fixed a `401` there.
- Parameter validation failures now return `400 INVALID_REQUEST`. The
  constraints the generator takes from the contract raise
  `HandlerMethodValidationException`, which was unhandled and fell through to
  the catch-all — so a caller sending `limit=1000` was told the server had
  failed when it had correctly refused them.

### Security

- **The audit log had no writers.** `AuditService` has been complete and tested
  since M1, and nothing in the application ever called it — the append-only log
  the security model describes recorded nothing. Workspace creation and rename,
  member add/change/remove, and API key creation and revocation now all write an
  entry. A test asserts the secret never reaches the log: an audit log is read by
  more people than the table it describes.
- **No cookie-authenticated write was possible for any real client.** Spring
  Security 6 masks the CSRF token by default, so the `XSRF-TOKEN` cookie carried
  a raw value while the header was expected to carry a masked one — a client
  echoing the cookie back was rejected, and because CSRF runs before
  authentication the symptom was a misleading `401`. Masking is now off (it
  defends against BREACH, which needs the token in a response body; this
  application never puts it there) and a filter ensures the cookie is issued on
  reads too, so a client whose first request is a `GET` has a token to send.
  ADR 0012.

  The suite was green throughout: every write test uses `.with(csrf())`, which
  builds a valid masked token internally and exercises a path no browser or
  `curl` can take. Found by driving the API from a shell, not from a test.
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
- `docs/api/known-inconsistencies.md` — the seven shapes found while writing
  the contract, and what happened to each.
- **`openapi-breaking` CI job** (`oasdiff`), failing any pull request that makes
  a breaking change to `/v1`. Verified by watching it reject one — and the
  rejection was real rather than staged: the change that introduced the gate is
  itself the last permitted break.

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
