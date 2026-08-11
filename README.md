# QuizForge

An asynchronous quiz and assessment engine. Tournaments open for a time
window, players enter on their own schedule, and results roll up to a
leaderboard — deliberately different from live synchronous quizzing.

API-first: the public REST API and its generated SDKs are the primary
product surface.

## Status

Pre-launch. Milestone M0 (foundation) is complete. See
[`docs/superpowers/specs/`](docs/superpowers/specs/) for the platform design
and the M0–M7 milestone map.

## Getting started

Requires Java 21, Docker, and Git.

```bash
git clone https://github.com/ARSH871-bot/QuizForge.git && cd QuizForge
cp .env.example .env          # fill in DB_PASSWORD
docker compose up -d          # starts PostgreSQL 16
cd apps/api
DB_PASSWORD=local-dev-only MAIL_USERNAME=unused MAIL_PASSWORD=unused ./mvnw spring-boot:run
```

The API listens on http://localhost:8080.

Credentials have no committed defaults by design, so the environment
variables above are required. Copy `.env.example` to `.env` for a permanent
local setup.

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
