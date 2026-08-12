# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file is updated in the same commit as the change it describes. A change
that is not worth a line here is not worth shipping.

## [Unreleased]

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
- M2 (content and authoring) implementation plan: immutable versioned
  questions, five question types with server-side grading, and a CSV/OpenTDB
  import pipeline.
- `STATUS.md` and `CHANGELOG.md`, plus a "Definition of done for any change"
  in `CONTRIBUTING.md` and a `docs-current` CI job that fails a pull request
  touching application source without updating the changelog, and blocks edits
  to already-applied Flyway migrations.

### Changed

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
