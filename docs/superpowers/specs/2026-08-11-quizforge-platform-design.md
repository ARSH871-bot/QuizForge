# QuizForge Platform — Design

**Date:** 2026-08-11
**Status:** Approved (design); milestones pending decomposition
**Supersedes:** the existing `backend/backend` coursework prototype

---

## 1. Context

QuizForge today is a single Spring Boot 3.3.5 module written as university coursework. It compiles and contains real domain thinking, but it is not a product: the entire API is unauthenticated, a live mail credential is committed to git, build artifacts are tracked, and the quiz-play path is very likely broken at runtime (`spring.jpa.open-in-view=false` combined with zero `@Transactional` boundaries and LAZY collections).

This document specifies its evolution into a launch-ready commercial product.

**On "evolution".** Approach A preserves the language, framework, and domain thinking — not the code. Realistically, the entity definitions and the OpenTDB integration survive largely intact; the controllers, services, and security configuration are replaced. The value retained is that you continue working in your strongest ecosystem and keep the modelling insight already earned, not that large amounts of the existing source are reused. This should be understood up front rather than discovered in M1.

## 2. Goals and constraints

**Goal.** A production-grade, multi-tenant quiz and assessment platform that is 100% ready to launch, so that launching is the only remaining step.

**Hard constraints:**

| # | Constraint | Consequence |
|---|---|---|
| C1 | ~$0/month until launch | Free-tier or self-hosted only; no service whose free tier expires |
| C2 | No vendor lock-in | Everything containerized and portable; no proprietary runtime APIs |
| C3 | API-first; developers are the first customer surface | The public API is the product, not an afterthought |
| C4 | Core must stay generic across all four segments | No segment-specific assumptions in the domain layer |
| C5 | Solo developer, ~35 hrs/week, 4–6 month horizon | Ruthless YAGNI; every milestone independently shippable |
| C6 | Evolve the Java core (Approach A) | Spring Boot modular monolith, not a rewrite |

**Non-goals.** Live synchronous "everyone answers at once" gameplay (that is Kahoot's product, and a different architecture). Native mobile apps. AI question generation. These may come later; they are not in scope.

## 3. Product definition

QuizForge is an **asynchronous tournament engine**. A tournament opens for a time window; players enter on their own schedule; results roll up to a leaderboard. This is deliberately different from live synchronous quizzing and is the product's wedge.

It is delivered through three surfaces, in this order:

1. **Public REST API + SDKs** (first — serves the developer segment and underpins the rest)
2. **Web dashboard** (workspace management, authoring, results)
3. **Embeddable player widget** (drop-in quiz experience for third-party sites)

Segment-specific integrations (LTI, SAML, Discord bot, SCORM) are additive work on top of the generic core, sequenced after launch.

## 4. Architecture

A **modular monolith** on Java 21 / Spring Boot 3.5, with boundaries enforced by Spring Modulith and ArchUnit tests that fail the build on violation. Modules communicate via published application events and explicit public APIs — never by reaching into another module's repositories.

| Module | Responsibility | Publishes |
|---|---|---|
| `platform` | Shared kernel: TypeIDs, error model, pagination, tenant context, audit log | — |
| `identity` | Accounts, sessions, API keys, workspaces, memberships, RBAC | `MemberInvited`, `MemberRemoved` |
| `content` | Question banks, questions + versioning, OpenTDB/CSV import | `QuestionBankUpdated` |
| `tournament` | Quiz templates, tournaments, scheduling, eligibility | `TournamentOpened`, `TournamentClosed` |
| `play` | Attempts, responses, grading, anti-cheat | `AttemptStarted`, `AttemptGraded` |
| `leaderboard` | Rankings, materialized standings | `LeaderboardUpdated` |
| `billing` | Plans, subscriptions, usage metering, Stripe | `SubscriptionChanged`, `QuotaExceeded` |
| `notify` | Email + webhook delivery via transactional outbox | — (consumes all of the above) |

### 4.1 Why a transactional outbox

`notify` consumes domain events and delivers email and developer webhooks through a Postgres-backed outbox drained by a background worker. This solves three problems with one mechanism:

- Removes synchronous SMTP from the request path (today, creating a quiz blocks on an SMTP send per user, with 25s timeouts)
- Guarantees at-least-once delivery with retry and backoff
- Is the same machinery that powers customer-facing webhooks

### 4.2 Explicitly rejected

Microservices, Kafka, Kubernetes, event sourcing, CQRS, GraphQL, and Redis. At the target scale Postgres handles caching, rate limiting, job queueing, and the outbox. Each rejected technology would add an operational surface with no measurable benefit and a real cost in the only scarce resource: focused solo time.

## 5. Domain model

```
Account ──* Membership *── Workspace         (tenant root; all below workspace-scoped)
                              │
              ┌───────────────┼──────────────────┐
              │               │                  │
        QuestionBank        Quiz             ApiKey / Webhook
              │           (template)
          Question             │
              └────────► Tournament          (scheduled run: window, rules, eligibility)
                              │
                           Attempt           (AGGREGATE ROOT for play)
                              │              STARTED → SUBMITTED → GRADED → EXPIRED
                          Response           (one answer; graded server-side, immutable once set)
                              │
                        Leaderboard          (derived, materialized on AttemptGraded)
```

### 5.1 Attempt is the central design decision

The current model has `Participation` (a bare user↔quiz row) and `Score` (a number), with actual answers persisted nowhere. Introducing `Attempt` as a stateful aggregate resolves, in one change:

| Existing defect | Resolved by |
|---|---|
| No resumability after disconnect | Attempt persists across requests with its own state |
| Unlimited resubmission | Submission is a state transition, not an insert |
| Score denominator drift (graded against all questions, played against 10) | Question set is frozen into the Attempt at creation |
| One-question-per-page flow persists nothing | Each `Response` is written as the player advances |
| Correct answers shipped to the client | Grading is server-side; answers never enter a payload |
| No time limit enforcement | Enforced against server-side `startedAt` |

### 5.2 Attempt policy (decision taken)

`Tournament.maxAttempts` (default `1`) and `Tournament.scoringPolicy` (`BEST | FIRST | LAST | AVERAGE`, default `FIRST`). Defaulting to 1/FIRST preserves current semantics while making practice modes and best-of-N a configuration change rather than a migration. Cheap now; expensive to retrofit.

### 5.3 Question types

`SINGLE_CHOICE`, `MULTI_CHOICE`, `TRUE_FALSE`, `NUMERIC` (with tolerance), `SHORT_TEXT` (with normalization rules). Questions are immutable and versioned; a Tournament pins question versions so edits cannot alter historical results.

## 6. API design

**Contract-first.** `openapi.yaml` is the source of truth in the repository. Spring server interfaces are generated from it; TypeScript, Python, and Go SDKs are generated from it. Drift between documentation and implementation becomes structurally impossible. A CI job diffs the spec against `main` and fails on breaking changes.

**Conventions:**

- Resource-oriented REST, plural nouns, `/v1/` major versioning, additive-only within a major
- **RFC 9457 Problem Details** for all errors, with stable machine-readable `type` URIs
- **`Idempotency-Key`** header honored on every mutating request, with a 24h replay window
- **Cursor pagination** with opaque cursors; offset pagination is never exposed
- **Per-key token-bucket rate limiting** in Postgres, surfaced via `RateLimit-*` headers ([draft-ietf-httpapi-ratelimit-headers](https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/) — this originally cited RFC 9331, which is the L4S congestion notification protocol and unrelated)
- **HMAC-SHA256 signed webhooks** with timestamp and replay window, exponential-backoff retries, and a delivery log visible in the dashboard

**Sandbox mode.** `qf_test_…` keys operate against isolated tenant data. This lets developers evaluate without risk and lets the product be demonstrated convincingly before it has real users.

## 7. Security and tenancy

- **Tenancy:** `workspace_id` on every row, **plus Postgres Row-Level Security** as defense-in-depth so that a forgotten predicate is a non-event rather than a cross-tenant breach
- **Passwords:** Argon2id (replacing BCrypt)
- **MFA:** TOTP
- **Web auth:** opaque, rotating, `httpOnly` / `SameSite=Lax` session cookies backed by Postgres
- **API auth:** keys stored as SHA-256 hashes, displayed exactly once, scoped permissions
- **Enterprise SSO:** OIDC/SAML deferred until the corporate segment is prioritized
- **AuthZ:** RBAC — `OWNER | ADMIN | EDITOR | VIEWER`
- **Audit log:** append-only, covering every privileged action
- **Anti-cheat:** server-side timing, per-attempt seeded answer shuffling, no correct answers in any play payload
- **Supply chain:** CodeQL, Trivy, dependency review, and secret scanning in CI; `SECURITY.md` with a disclosure policy

### 7.1 Immediate remediation of existing exposure

1. Revoke the committed Gmail app password at Google (it is in git history; removing the file is insufficient)
2. Purge it from history with `git-filter-repo`, then force-push and rotate
3. `git rm -r --cached` the tracked `target/` directory and add a root `.gitignore`
4. Remove the hardcoded default admin credentials from `BackendApplication`

## 8. Data layer

- **Postgres 16**, **Flyway** migrations, `ddl-auto` removed entirely
- **Expand/contract** migration pattern for zero-downtime deploys
- **Explicit `@Transactional` boundaries at the application-service layer** — this alone eliminates the entire `LazyInitializationException` class of defect present today
- Entities are **never** serialized to JSON; MapStruct DTOs at every boundary
- Optimistic locking (`@Version`) on `Attempt` and `Quiz`
- All timestamps UTC `Instant`; tournaments carry an IANA zone for display only
- **Prefixed TypeIDs/ULIDs** (`att_01HQ…`) — sortable, opaque, non-enumerable, debuggable
- Nightly `pg_dump` to Cloudflare R2, with a **rehearsed** restore procedure documented in a runbook
- GDPR data export and hard-delete paths

## 9. Testing

| Layer | Tool | Gate |
|---|---|---|
| Unit | JUnit 5, no Spring context | Fast; the bulk of tests |
| Integration | Testcontainers, real Postgres | Every module |
| Contract | OpenAPI validation | Request/response conformance |
| Architecture | ArchUnit + Spring Modulith | Build fails on boundary violation |
| Mutation | PIT, domain modules only | Surviving mutants block merge |
| E2E | Playwright | Critical player and admin journeys |
| Load | k6 | Pre-launch only |

Coverage percentage is explicitly **not** a gate; surviving mutants in domain logic are. This avoids the common failure of tests written to satisfy a number.

## 10. CI/CD and infrastructure

**CI (GitHub Actions):** build → test → ArchUnit → CodeQL → Trivy → dependency review → secret scan → OpenAPI breaking-change diff.

**CD:** OCI images built with Jib, pushed to GHCR, deployed with **Kamal 2** — zero-downtime container deploys onto a plain VM with health checks and automatic rollback. This provides the substantive benefits of Kubernetes at a small fraction of its operational cost, and keeps the deployment portable to any provider.

Migrations run as a discrete pre-deploy step. Feature flags are DB-backed.

**Observability:** OpenTelemetry → self-hosted Grafana / Loki / Tempo / Prometheus on the same free instance. Structured JSON logs with trace correlation. Sentry free tier for errors. Defined SLOs with error budgets and alerting.

## 11. Frontend

**Next.js 15 / React 19 / TypeScript.** Three surfaces:

1. Marketing site + API documentation (generated from `openapi.yaml`, with an interactive playground)
2. Application dashboard (authoring, workspace management, results)
3. Embeddable player widget — iframe-isolated web component, separately bundled and versioned

**Design system:** Tailwind + shadcn/ui as a base, **deliberately re-themed**. Default shadcn is immediately recognizable; a product positioned as best-in-class cannot look like a template.

**Quality gates:** WCAG 2.2 AA with axe in CI, keyboard-complete, screen-reader tested. `next-intl` i18n from day one — inexpensive now, punishing to retrofit. Enforced performance budgets (LCP < 1.5s, CLS < 0.1) via Lighthouse CI.

The player experience carries the product: optimistic UI, a timer that survives refresh, and tolerance of brief connectivity loss.

## 12. Repository and process

**Monorepo:**

```
/apps/api          Spring Boot modular monolith
/apps/web          Next.js dashboard + marketing
/apps/docs         API documentation site
/packages/sdk-ts   Generated TypeScript SDK
/packages/sdk-py   Generated Python SDK
/packages/sdk-go   Generated Go SDK
/infra             Kamal config, compose files, Grafana provisioning
/docs/adr          Architecture Decision Records
openapi.yaml       Source of truth
```

**Version control discipline:**

- Trunk-based development with short-lived branches
- **Conventional Commits** enforced by commitlint
- **Signed commits**, linear history, no force-push to `main`
- Branch protection with required status checks **even when solo** — it forces a deliberate read of your own diff
- `release-please` for automated changelogs and semantic versioning
- Renovate for dependency currency, patches grouped and auto-merged
- `CODEOWNERS`, PR and issue templates

**ADRs.** Every significant decision recorded in `/docs/adr` with context, options considered, decision, and consequences. This is the practice that most reliably distinguishes a world-class repository from a merely tidy one.

**Legal and commercial readiness:** Terms of Service, Privacy Policy, DPA template, cookie consent, GDPR/CCPA handling. Stripe fully integrated in **test mode** with plans defined, usage metering live, and idempotent webhook handling — so that going live is a key swap.

## 13. Cost ledger

| Service | Purpose | Cost |
|---|---|---|
| Oracle Cloud Always Free (4 ARM cores / 24GB) | App, Postgres, Grafana stack | $0 |
| GitHub Free | Repository, 2000 CI min/mo, GHCR | $0 |
| Cloudflare Free | DNS, CDN, WAF, R2 backups (10GB) | $0 |
| Resend Free | Transactional email (3k/mo) | $0 |
| Sentry Free | Error tracking (5k events/mo) | $0 |
| Stripe | Billing, in test mode | $0 until charging |
| Domain | — | ~$10/yr |

**Total: ~$10/year until launch.** Everything is containerized and Kamal-deployed; migrating to a €4/mo Hetzner box is a single configuration change.

**Risk on C1.** Oracle Cloud's Always Free ARM capacity is frequently unavailable in popular regions, and Oracle reclaims persistently idle resources. This is the single weakest link in the $0 posture. Mitigation: the entire stack is Kamal-deployed to a generic VM, so the fallback is a €4/mo Hetzner CX22 and one config change — not a re-architecture. Treat Hetzner as the expected steady state and Oracle as an opportunistic saving.

## 14. Milestone decomposition

This design is too large for one implementation plan. It decomposes into eight independently shippable milestones, each of which gets its own spec and plan:

| # | Milestone | Outcome |
|---|---|---|
| M0 | **Foundation & remediation** | Monorepo, secret purge, CI skeleton, Flyway, Testcontainers, ADR practice established |
| M1 | **Identity & tenancy** | Accounts, workspaces, RBAC, sessions, API keys, RLS |
| M2 | **Content & authoring** | Question banks, versioned questions, import pipeline |
| M3 | **Tournament & play engine** | The `Attempt` aggregate, grading, anti-cheat, leaderboards |
| M4 | **Public API & SDKs** | OpenAPI contract, generated SDKs, webhooks, rate limiting, sandbox |
| M5 | **Web dashboard** | Next.js app, design system, authoring and results UI |
| M6 | **Player experience & widget** | Player flow, embeddable widget, accessibility, performance |
| M7 | **Commercial & launch readiness** | Billing, legal, observability, runbooks, load testing, launch checklist |

M0–M3 are strictly sequential. M4 and M5 can overlap. M6 depends on M5. M7 is last.

## 15. Decisions taken without explicit approval

Recorded here so they can be revisited:

1. **Attempt policy is configurable** (`maxAttempts`, `scoringPolicy`), defaulting to current single-attempt semantics
2. **Questions are immutable and versioned**, with tournaments pinning versions, so edits cannot alter historical results
3. **Redis is excluded**; Postgres serves caching, rate limiting, and queueing
4. **Enterprise SSO deferred** to the corporate-segment milestone
5. **Live synchronous gameplay is out of scope** — async tournaments are the wedge

## 16. Open questions

None blocking. The first implementation plan (M0) can begin on approval.
