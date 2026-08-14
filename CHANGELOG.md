# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file is updated in the same commit as the change it describes. A change
that is not worth a line here is not worth shipping.

## [Unreleased]

### Added

- **Playing an attempt (M3).** Start, per-question retrieval, answer and
  submit. Answers are graded and persisted as the player advances, so an
  attempt survives a disconnect and submission merely aggregates stored
  outcomes. Options are shuffled per attempt and the order stored, so a
  refresh does not reshuffle under the player while two players still get
  different orders.
- **`TournamentAccess`**, the tournament module's published API, so `play` can
  read a tournament without reaching into its internals.
- **The `Attempt` aggregate (M3).** One player's run at a tournament, as
  durable state rather than a marker row. Replaces the prototype's
  disconnected `Participation` and `Score`, fixing six defects at once:
  resumability after a disconnect, idempotent submission, a score denominator
  frozen at creation so a player is never graded against questions they did
  not see, per-question responses that the paginated flow can persist,
  server-side grading, and a time limit computed from the server clock rather
  than accepted from a request.
- **Tournaments (M3).** A scheduled run of questions drawn from a bank, open
  for a window. State (SCHEDULED/OPEN/CLOSED) is derived from the clock rather
  than stored, so no scheduled job transitions it and a row can never disagree
  with the calendar. A tournament asking for more questions than its bank holds
  is rejected at creation, not discovered by the first player.
- **`QuestionAccess`**, the content module's published API, so `tournament`
  (and later `play`) can ask about questions without reaching into
  `content.app` or `content.domain`.
- `LICENSE` (proprietary), `CODE_OF_CONDUCT.md`, `SUPPORT.md`, and README
  status badges — completing GitHub's community standards checklist.
- Milestone tags and GitHub Releases: `v0.1.0` (M0), `v0.2.0` (M1), `v0.3.0`
  (M2). Three milestones had shipped with no tags at all, so there were no
  restore points and no visible version history.

- **Identity and tenancy (M1).** Accounts with Argon2id hashing at OWASP
  parameters, workspaces with an OWNER/ADMIN/EDITOR/VIEWER permission matrix,
  hashed sessions and API keys, an append-only audit log, and Postgres
  Row-Level Security policies.
- `POST /v1/auth/register`, `/login`, `/logout`; `GET /v1/auth/me`;
  `POST /v1/auth/request-password-reset`.
- Typed prefixed identifiers (`acc_…`, `wsp_…`) backed by UUIDv7, rendered only
  at the API boundary.
- RFC 9457 Problem Details for every error, with stable machine-readable codes.
- **OpenTDB import (M2).** Replaces the legacy `OpenTDBService`. Requests
  base64 rather than URL encoding (the legacy path double-decoded any answer
  containing a percent sign), fails fast on rate limiting instead of sleeping
  six seconds per category, and de-duplicates through the same content hash as
  every other import.
- **CSV import (M2).** Partial success is the normal case: one malformed row
  does not discard the rest, failures are reported with the line number a human
  sees in a spreadsheet, and re-importing the same file is a no-op rather than
  an error.
- **Server-side grading (M2).** A grader per question type behind a registry.
  Multi-choice requires set equality; numeric compares within absolute
  tolerance; short text normalises without fuzzy matching. A null or
  unparseable answer is incorrect, never an error.
- **Tenant isolation for content.** RLS policies on `question_bank` and
  `question`, proven by a test that asks for another workspace's rows and gets
  nothing.
- **Question banks and immutable authoring (M2).** Authoring writes version 1;
  revising inserts a new row sharing the lineage and supersedes the previous
  one, leaving it byte-identical so a tournament can pin exactly what a player
  saw. Duplicates are rejected per bank by normalised content hash.
- **`WorkspaceAccess`**, identity's published authorization API. Other modules
  ask permission questions through it rather than reaching into
  `identity.app` or `identity.domain`, so the permission matrix stays internal.
- **Question types (M2).** Five types — SINGLE_CHOICE, MULTI_CHOICE,
  TRUE_FALSE, NUMERIC, SHORT_TEXT — with immutable payload records that
  validate on write, since a JSONB column cannot enforce shape itself.
- **Content schema (M2).** `question_bank` and `question` tables, with
  lineage/version uniqueness, per-bank content-hash de-duplication, and a
  partial index on the current version of each lineage.
- **Runtime enforcement of Row-Level Security.** Every transaction carrying a
  tenant now assumes the `NOBYPASSRLS` role `quizforge_app` and sets
  `app.workspace_id`, both transaction-locally. Proven by a test asserting a
  workspace cannot read another's rows even when explicitly asking for them.
- **Foundation (M0).** Monorepo layout, Flyway-owned schema on PostgreSQL,
  Testcontainers harness, Spring Modulith module skeleton with ArchUnit
  guardrails, CI with build, gitleaks and PR-title linting.
- SpotBugs with FindSecBugs, replacing CodeQL on a private repository.
- Mailpit in the local stack, so email paths can be developed with no account.
- Eight ADRs in `docs/adr/`.
- M3 (tournament and play engine) implementation plan, including the retirement of the legacy package.
- M2 (content and authoring) implementation plan: immutable versioned
  questions, five question types with server-side grading, and a CSV/OpenTDB
  import pipeline.
- `STATUS.md` and `CHANGELOG.md`, plus a "Definition of done for any change"
  in `CONTRIBUTING.md` and a `docs-current` CI job that fails a pull request
  touching application source without updating the changelog, and blocks edits
  to already-applied Flyway migrations.

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

- `/v1/**` now requires authentication. `/api/**` (legacy) remains open until
  M3 retires it.
- CSRF protection enabled for cookie-authenticated requests, exempting
  bearer-token calls and the pre-session auth path.
- Passwords: minimum length raised from 6 to 12, per NIST SP 800-63B.
- Email is optional and disabled by default; the application starts with no
  mail configuration at all.
- Java 17 → 21, Spring Boot 3.3.5 → 3.5.6.

### Changed

- The legacy `Question` entity, `QuestionRepository` and `question` table are
  all renamed with a `Legacy` prefix, freeing those names for the content
  module. Spring, Spring Data and Hibernate each derive a distinct identifier
  from the simple class name, and all three collided.
- The legacy `question` table is renamed to `legacy_question`, freeing the name
  for the new content model. Its entity mapping gains an explicit
  `@CollectionTable` so Hibernate still finds `question_options`. The legacy
  table is deleted outright in M3.

### Removed

- `spring.jpa.hibernate.ddl-auto=update`. Flyway owns the schema; Hibernate
  validates only.
- MySQL driver and dialect configuration. PostgreSQL only.
- Hardcoded default admin and player credentials from application startup.
- Committed mail credentials from the working tree.
- Tracked build artifacts (`target/`) and IDE configuration (`.idea/`).

### Security

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

- Legacy BCrypt password encoder replaced with Argon2id. Existing BCrypt
  hashes remain verifiable, so accounts upgrade on next login.
- Session and API key material stored only as SHA-256 digests.
- Authentication timing equalised: an unknown email costs the same as a wrong
  password.
- Password reset no longer returns the token in the HTTP response. A
  regression test enforces this.

### Known gaps

- A mail credential remains in git history. It is referenced by no code and
  revoking it breaks nothing. Tracked in
  [#2](https://github.com/ARSH871-bot/QuizForge/issues/2).
- Branch protection is advisory only (a local `pre-push` hook), because
  server-side protection requires a public repository. See ADR 0007.
