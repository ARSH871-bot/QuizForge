# QuizForge

[![CI](https://github.com/ARSH871-bot/QuizForge/actions/workflows/ci.yml/badge.svg)](https://github.com/ARSH871-bot/QuizForge/actions/workflows/ci.yml)
[![CodeQL](https://github.com/ARSH871-bot/QuizForge/actions/workflows/github-code-scanning/codeql/badge.svg)](https://github.com/ARSH871-bot/QuizForge/security/code-scanning)
[![Release](https://img.shields.io/github/v/release/ARSH871-bot/QuizForge?sort=semver)](https://github.com/ARSH871-bot/QuizForge/releases)
[![Java 21](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)](https://spring.io/projects/spring-boot)
[![Licence: Proprietary](https://img.shields.io/badge/licence-proprietary-lightgrey)](LICENSE)

An asynchronous quiz and assessment engine. Tournaments open for a time
window, players enter on their own schedule, and results roll up to a
leaderboard — deliberately different from live synchronous quizzing.

API-first: the public REST API and its generated SDKs are the primary
product surface.

## Status

Pre-launch. M0 (foundation), M1 (identity and tenancy), M2 (content and
authoring) and M3 (tournaments and play) are complete: a player can start a
tournament, answer question by question, submit, and appear on a leaderboard,
entirely through authenticated `/v1` endpoints. M4 (the public API contract and
SDKs) is next.

**[STATUS.md](STATUS.md) is the single source of truth** for where the product
stands, what is safe to do with the code, and every known gap. Read it before
deploying anything.

See [`docs/superpowers/specs/`](docs/superpowers/specs/) for the platform
design and the M0–M7 milestone map, and [CHANGELOG.md](CHANGELOG.md) for what
has changed.

## Getting started

Requires Java 21, Docker, and Git.

```bash
git clone https://github.com/ARSH871-bot/QuizForge.git && cd QuizForge
cp .env.example .env          # fill in DB_PASSWORD
docker compose up -d          # starts PostgreSQL 16
cd apps/api
DB_PASSWORD=local-dev-only ./mvnw spring-boot:run
```

The API listens on http://localhost:8080.

`DB_PASSWORD` has no committed default by design, so it must be supplied.
Copy `.env.example` to `.env` for a permanent local setup.

**Then follow [docs/api/README.md](docs/api/README.md)** — an account, a
workspace, a question bank, a tournament, a played attempt and a leaderboard,
one runnable command at a time. It is the same walkthrough as
[`docs/api/quickstart.sh`](docs/api/quickstart.sh), which runs it unattended.

## The API

| | |
|---|---|
| [Getting started](docs/api/README.md) | Clone to a played tournament, every command runnable |
| [`openapi.yaml`](openapi.yaml) | The contract. 41 operations, every error code, no drift |
| [API reference](docs/api/spec/) | The contract rendered. `npm run spec`, then <http://localhost:8090> |
| [TypeScript SDK](packages/sdk-typescript) | Cursors followed, idempotency keys generated, typed errors |

The reference is not hosted yet: GitHub Pages is unavailable for a private
repository on the Free plan, so it is served locally and its publishing
workflow is manual until that changes ([#83](https://github.com/ARSH871-bot/QuizForge/issues/83)).

### Email

Email needs no configuration and no account. `EMAIL_TEST_MODE` defaults to
`true`, so the application logs messages instead of sending them.

To exercise the real sending path locally, `docker compose up -d` also starts
**Mailpit**, an SMTP server that accepts everything and delivers nothing:

```bash
EMAIL_TEST_MODE=false MAIL_HOST=localhost MAIL_PORT=1025 DB_PASSWORD=local-dev-only ./mvnw spring-boot:run
```

Captured messages appear at <http://localhost:8025>. Nothing leaves your
machine, and no credential is required.

In production a dedicated provider is used, sending from an address on the
project's own domain. Personal SMTP credentials are deliberately never used:
they throttle at a few hundred messages a day, harm deliverability, and tie
transactional mail to an individual's account.

## Running tests

```bash
cd apps/api && ./mvnw verify
```

Integration tests start their own PostgreSQL container via Testcontainers, so
Docker must be running. No manual database setup is needed, and the suite
makes no outbound network calls — the OpenTDB bootstrap is disabled during
tests via `quizforge.opentdb.bootstrap-enabled=false`.

## Layout

| Path | Contents |
|---|---|
| `apps/api` | Spring Boot modular monolith |
| `docs/adr` | Architecture Decision Records |
| `docs/superpowers/specs` | Design specifications |
| `docs/superpowers/plans` | Implementation plans |
| `STATUS.md` | Current state, known gaps, what is safe to deploy |
| `CHANGELOG.md` | What changed, and when |
| `LICENSE` | Proprietary. See the file for why, and what it would take to change |

## Architecture

A modular monolith on Spring Modulith. Eight modules — `platform`,
`identity`, `content`, `tournament`, `play`, `leaderboard`, `billing`,
`notify` — declare their permitted dependencies in `package-info.java`.

Boundaries are enforced by tests, not convention:

- `ModularityTest` fails the build if a module reaches outside its declared
  dependencies.
- `ArchitectureTest` prevents new code from depending on the legacy
  `cs.quizzapp` package, and stops controllers talking directly to
  repositories.

The schema is owned by Flyway; Hibernate is set to `validate` and may never
alter it.

Read [`docs/adr/`](docs/adr/) for why things are the way they are.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Commits follow Conventional Commits,
enforced locally by a git hook and on pull request titles in CI.

## Versioning

Milestones are tagged and released: `v0.1.0` (M0), `v0.2.0` (M1), `v0.3.0`
(M2). See [Releases](https://github.com/ARSH871-bot/QuizForge/releases).

Semantic versioning of the **public API** begins at M4, when the OpenAPI
contract and generated SDKs exist and a version number starts meaning
something to a consumer. Until then the numbers track milestones and act as
restore points. `1.0.0` is launch.

## Licence

Proprietary — see [LICENSE](LICENSE). This is a deliberate default for a
commercial product, and a one-way door in the other direction.
