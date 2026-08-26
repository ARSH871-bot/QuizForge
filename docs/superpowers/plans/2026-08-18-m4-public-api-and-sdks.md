# M4 — Public API & SDKs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the API-first product actually usable by an API consumer. Publish `openapi.yaml` as the source of truth, generate server interfaces from it so documentation cannot drift, close the endpoint gaps that make the product unusable over HTTP, and ship one SDK that proves the generation pipeline end to end.

**Architecture:** Contract-first, per §6 of the platform design. `openapi.yaml` at the repository root is the source of truth; `openapi-generator` produces Spring interfaces that existing and new controllers *implement*, so a controller that diverges from the contract fails to compile. The same file generates the TypeScript SDK. Idempotency, cursor pagination and rate limiting are cross-cutting concerns implemented once in `platform` and applied by filter, not repeated per controller.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.12, PostgreSQL 16, Flyway, Testcontainers, ArchUnit, openapi-generator, Spectral, oasdiff, TypeScript.

## The gap this milestone actually closes

M3's completion notes said the `/v1` surface was smaller than the legacy one. That undersold it. The current HTTP surface is:

| Module | Services that exist | Reachable over HTTP |
|---|---|---|
| `identity` | `AccountService`, `WorkspaceService`, `ApiKeyService`, `AuditService` | register, login, logout, me, request-password-reset |
| `content` | `QuestionBankService`, `QuestionService`, CSV + OpenTDB importers | **nothing — the module has no `web` package** |
| `tournament` | `TournamentService` | list only |
| `play` | `AttemptService` | full |
| `leaderboard` | `LeaderboardService` | standings |

So a developer holding a valid credential **cannot create a workspace, an API key, a question bank, a question, or a tournament.** They can register, log in, and play a tournament that somebody else created out of band — which nobody can do, because creating one also has no endpoint.

There is no endpoint that mints an API key, so the API-first product has no way to issue the credential its primary auth mechanism depends on. `ApiKeyService` has been complete since M1 and unreachable ever since.

This is the milestone where the product becomes something a developer can use without a database console. Everything else in M4 is in service of that.

## Global Constraints

Everything in M1–M3's constraints still applies. In addition:

- **Migrations start at V12.** V1–V11 exist. Never edit an applied migration.
- **The contract is the source of truth, not the code.** If a controller and `openapi.yaml` disagree, the contract is right and the controller is a bug. This is enforced by compilation, not by review.
- **Additive-only within `/v1`.** No field removed, no field's type changed, no enum value removed, no required request field added. `oasdiff` fails the build on a breaking change. Breaking changes wait for `/v2`, which does not exist and should not exist for a long time.
- **Every mutating endpoint honours `Idempotency-Key`.** Not "most". A developer who cannot safely retry a request will build their own deduplication, badly.
- **No offset pagination is ever exposed.** Not on any endpoint, not "just for the dashboard". Offset pagination is wrong under concurrent writes and the wrongness is invisible until a customer reports duplicates.
- **Correct answers still never leave the server during play.** M3's rule, restated because M4 adds authoring endpoints that legitimately *do* return correct answers, and the two must not be confused. Authoring requires `EDITOR`; play does not.

## Prerequisites

```bash
cd apps/api && ./mvnw -B clean verify   # expect BUILD SUCCESS, 161 tests
docker compose ps                        # postgres and mailpit healthy
gh release view v0.4.0                   # M3 shipped
```

If any fail, fix that before starting. A plan executed on a red build produces two problems that look like one.

## Scope decisions taken

Recorded so they can be revisited rather than rediscovered.

1. **TypeScript SDK only in M4.** Python and Go follow once the pipeline is a solved problem rather than three unsolved ones in parallel. TypeScript first because M5 is a Next.js dashboard that will consume it — the SDK gets a real integration test for free, which a Python SDK would not get until someone wrote one.

2. **Webhooks and sandbox mode move out of M4.** Both are in §6 of the spec and both are real commitments, but neither blocks a developer's first successful call. They become M4.5, scheduled after M5 proves the API against a real consumer. Stated plainly: this is a scope cut, not a cancellation, and `STATUS.md` must say so rather than implying the spec shrank.

3. **API keys are minted with a `qf_live_` prefix from now on.** Sandbox is deferred, but reserving the prefix costs nothing today and avoids reissuing every customer key later. `SecurityConfig` already matches `Bearer qf_`, so bare `qf_` keys from M1 keep working. When sandbox lands, `qf_test_` slots in beside it with no migration.

4. **Generated interfaces, hand-written controllers.** `interfaceOnly=true`. The generator produces the interface and the DTOs; controllers implement them and keep their own bodies. Generating controller bodies means generated code in `src/main/java`, which nobody can debug and everybody eventually edits.

5. **The SDK is generated types plus a hand-written ergonomic layer.** `openapi-typescript` emits the types; roughly 200 lines of hand-written client provide auth, retries, idempotency-key generation and async pagination iteration. Fully generated clients are usually unpleasant to use, and the unpleasantness is the first thing a developer evaluating the product notices.

6. **Rate limits are per API key, not per workspace or IP.** A key is the unit a developer can reason about and rotate. Per-workspace punishes a customer for one runaway script; per-IP breaks behind NAT and is trivially evaded.

---

## Task 1: The contract, published and linted

Write `openapi.yaml` describing the **existing** surface first — the 12 endpoints that already work — before adding anything new. Describing what exists is how you find out what the contract has to be able to express.

- [x] Create `openapi.yaml` at the repository root, OpenAPI 3.1
- [x] Describe every existing `/v1` endpoint: auth (5), tournaments list, standings, attempts (5)
- [x] Model `Problem` once (RFC 9457) with the `code` extension, and reference it from every error response
- [x] Document every `ErrorCode` value as an enum on `Problem.code` — the machine-readable codes are the contract's most valuable part and are currently discoverable only by reading Java
- [x] Model typed identifiers as `string` with `pattern` and `example` (`acc_`, `wsp_`, `trn_`, `att_`)
- [x] Add `securitySchemes`: `bearerApiKey` (Bearer `qf_…`) and `sessionCookie`
- [x] Add Spectral (`.spectral.yaml`) with the OWASP and standard OAS rulesets; fix every finding rather than suppressing it
- [x] CI job `openapi-lint` running Spectral, wired into `ci.yml`
- [x] Add `openapi.yaml` to the `docs-current` guard: a change under `apps/api/src/main/java/**/web/**` without a change to `openapi.yaml` fails the build

**Verification:** Spectral passes clean. The spec renders in Swagger UI. Every documented example request, executed by hand against a running instance, returns the documented status and shape.

**Done when:** the contract describes reality exactly, including the parts of reality that are embarrassing.

## Task 2: Generate server interfaces and fail on drift

- [x] Add `openapi-generator-maven-plugin`, `generatorName=spring`, `interfaceOnly=true`, `useSpringBoot3=true`, output to `target/generated-sources`
- [x] Retrofit all five existing controllers to `implements` their generated interface
- [x] Resolve every mismatch the compiler surfaces **by fixing the code or the contract deliberately** — recording which, and why, for any case where the contract was wrong
- [x] Add `oasdiff` CI job comparing the PR's `openapi.yaml` against `main`'s, failing on breaking changes
- [x] Verify the breaking-change gate actually gates: open a throwaway PR removing a required response field, confirm CI goes red, close it

**Verification:** `./mvnw verify` compiles with generated interfaces. The drift gate has been *seen* rejecting a breaking change — a configured gate nobody has watched fail is not a gate, which is the lesson from ADR 0007's Renovate entry and ADR 0009's ruleset test.

**Done when:** a controller cannot silently diverge from the published contract.

## Task 3: Workspaces, members and API keys

The credential-issuing gap. Highest priority of the CRUD tasks: without it every
other endpoint is unreachable by an API consumer.

**Paths differ from the sketch below as originally written.** It proposed
`/v1/workspaces/{id}/members` and `/v1/workspaces/{id}/api-keys`. Those were
built without the id: the workspace is already in scope, selected by header or
implied by the API key, and repeating it in the path creates a second way to
name a tenant — which then has to be reconciled when the two disagree. Having
two tenant-selection mechanisms is how the cross-tenant defects in ADR 0010 were
written. `/v1/workspaces` itself stays account-scoped, because a new account has
no workspace and must still be able to create one.

- [x] `POST /v1/workspaces` — create; caller becomes `OWNER`
- [x] `GET /v1/workspaces` — list the caller's workspaces
- [x] `GET /v1/workspaces/current`, `PATCH /v1/workspaces/current` — read, rename
- [x] `GET /v1/members`, `POST` (add by email), `PATCH /v1/members/{accountId}`
      (change role), `DELETE /v1/members/{accountId}` (remove)
- [x] `POST /v1/api-keys` — mint with `qf_live_` prefix, **return the plaintext
      exactly once**, persist only the SHA-256 digest
- [x] `GET /v1/api-keys` — list metadata only: prefix, label, created, last used.
      Never the key
- [x] `DELETE /v1/api-keys/{keyId}` — revoke, effective immediately
- [x] Every one of these writes an audit entry via `AuditService`
- [x] Enforce the permission matrix through the identity module's services
      rather than by reading roles in the controller

**Tests:** the last `OWNER` cannot be demoted or removed. A revoked key is
rejected on the very next request. A key's plaintext appears in exactly one
response across its lifetime. A `VIEWER` cannot manage members, and an API key
cannot mint another API key.

**Found while doing this, both fixed here:**

- The audit log had **no writers at all**. `AuditService` was complete and tested
  since M1 and nothing called it.
- **No cookie-authenticated write worked for any real client** — Spring
  Security's default CSRF token masking against a cookie the client is meant to
  echo. Invisible to the suite, because `.with(csrf())` builds the token it is
  supposed to be verifying. ADR 0012.

## Task 4: Question banks, questions and imports

- [x] Create the `content.web` package — it did not exist
- [x] `POST|GET /v1/question-banks`, `GET|DELETE /v1/question-banks/{bankId}`
- [x] `POST /v1/question-banks/{bankId}/questions` — author (writes version 1)
- [x] `GET /v1/question-banks/{bankId}/questions` — current versions only
- [x] `GET /v1/questions/{questionId}` — a specific version; `PATCH` inserts a
      new version and supersedes, per M2's immutability rule
- [x] `GET /v1/questions/{questionId}/versions` — the lineage
- [x] `DELETE /v1/questions/{questionId}` — retire
- [x] `POST /v1/question-banks/{bankId}/imports/csv` with `text/csv`
- [x] `POST /v1/question-banks/{bankId}/imports/opentdb`
- [x] Document the payload shapes as a discriminated union

**Corrections to this task as written:**

- It said "five payload shapes". There are **three**: `ChoicePayload` serves
  `SINGLE_CHOICE`, `TRUE_FALSE` and `MULTI_CHOICE`, with different validation
  rules per type. The union discriminates on the shape and the contract
  documents the five rules in a table — five branches where three are identical
  would be fake precision.
- Import failures were restructured from `messages: ["row 3: ..."]` to
  `failures: [{ line, message }]`. Verifying that line numbers "survive the HTTP
  boundary" is not worth much if the only way to read one is to parse prose, and
  the shape is free to change now rather than after an SDK ships.

**Found while doing this, both fixed here:** a bank description was accepted and
silently discarded, and the contract allowed 2000 characters against a
`VARCHAR(500)` column.

## Task 5: Tournament lifecycle

- [x] `POST /v1/tournaments` — create; keeps the validation that a tournament
      cannot request more questions than its bank holds
- [x] `GET|PATCH /v1/tournaments/{id}` — `PATCH` refused once `OPEN`, with a
      `code` and a detail naming the state rather than a bare 409
- [x] `DELETE /v1/tournaments/{id}` — only while `SCHEDULED`, only with zero
      attempts
- [x] `GET /v1/tournaments/{id}/attempts-summary` — the organiser's view

**Paths differ from the sketch:** created under `/v1/tournaments` rather than
`/v1/workspaces/{id}/tournaments`, for the reason recorded in Task 3 — the
workspace is already in scope and a second way to name a tenant has to be
reconciled when the two disagree.

The organiser's view is `attempts-summary` rather than `attempts`, because
`POST /v1/tournaments/{id}/attempts` already exists and means something else:
starting one. It also lives in `play.web`, not `tournament.web`, because that is
where attempts are.

**A module boundary ran the wrong way.** Deleting needs the attempt count, but
`tournament` cannot depend on `play` — `play` already depends on it, and a cycle
is what the boundaries exist to prevent. Solved with an outbound port:
`TournamentUsage` is declared in `tournament`, where the question is asked, and
implemented in `play`, where the data is.

**Found while testing:** the zero-attempts rule is unreachable through the API.
Attempts can only start while a tournament is `OPEN`, and an `OPEN` tournament
can never be amended back to `SCHEDULED`, so the state rule always refuses first.
It is kept as defence in depth and is exercised by a test that moves the window
directly in the database — defence in depth that has never been executed is an
assumption, and that test is also what proves the port is wired.

## Task 6: Cursor pagination

- [ ] `platform.web.Cursor`: opaque base64 of `(created_at, id)`, keyset — never offset
- [ ] Standard envelope `{ data: [...], nextCursor: string|null }` applied to every list endpoint
- [ ] Reject a malformed or foreign cursor with `INVALID_CURSOR`, never a 500
- [ ] `limit` parameter, default 25, maximum 100, documented in the contract

**Tests:** inserting a row mid-pagination neither duplicates nor skips an item — the property offset pagination fails and the reason this is not configurable.

## Task 7: Idempotency keys

- [ ] V12: `idempotency_key` table — `(workspace_id, key)` unique, plus `request_hash`, `response_status`, `response_body`, `created_at`
- [ ] Filter in `platform` applying to every `POST`/`PATCH`/`DELETE` under `/v1`
- [ ] Replay within 24h returns the stored response verbatim with `Idempotency-Replayed: true`
- [ ] Same key with a *different* request body is `422 IDEMPOTENCY_KEY_REUSED` — never silently the old response
- [ ] Scheduled purge of records older than 24h, using M3's advisory-lock pattern so it is safe across instances
- [ ] RLS policy on the table, consistent with every other tenant-scoped table

**Tests:** the same create replayed twice produces one row and two identical responses. A concurrent duplicate — two threads, same key, simultaneously — still produces one row.

## Task 8: Rate limiting

- [ ] V12 (same migration): `rate_limit_bucket` — token bucket keyed by API key id
- [ ] Filter applying limits per key; session-authenticated requests get a separate, looser default
- [ ] `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers per RFC 9331 on **every** response, not only on 429
- [ ] `429` returns Problem Details with `RATE_LIMITED` and a `Retry-After` header
- [ ] Limits configurable per workspace, defaulting to a documented free-tier number, so M7's billing tiers have something to change

**Tests:** the limit is actually enforced under burst. Headers are present on a successful response. Two different keys in the same workspace do not share a bucket.

## Task 9: The TypeScript SDK

- [ ] `packages/sdk-typescript/`, generated types via `openapi-typescript` from the same `openapi.yaml`
- [ ] Hand-written client: `new QuizForge({ apiKey })`, one method per resource group
- [ ] Automatic `Idempotency-Key` generation for mutating calls, overridable by the caller
- [ ] `for await (const q of qf.questions.list(bankId))` — async iteration that follows cursors
- [ ] Typed errors: a `QuizForgeError` carrying the `code`, so consumers branch on a stable string rather than a message
- [ ] Retry with exponential backoff on 429 and 5xx, honouring `Retry-After`; never retry a non-idempotent call without a key
- [ ] `README.md` with a copy-pasteable quickstart that works start to finish
- [ ] CI job building and type-checking the SDK; regenerate and fail if the committed types differ from the contract

**Not in this task:** publishing to npm. That claims a public package name and is the owner's call, not an implementation detail — see *Owner actions required*.

## Task 10: Developer onboarding

- [ ] `docs/api/README.md`: authenticate, create a workspace, mint a key, create a bank, import questions, create a tournament, play it — one continuous narrative, every command runnable
- [ ] Publish the rendered spec (Swagger UI or Scalar) via GitHub Pages
- [ ] Link it from `README.md`
- [ ] Execute the entire quickstart against a clean `docker compose up` and fix whatever does not work

**Done when:** a developer who has never seen this repository can go from clone to a played tournament using only the published documentation.

---

## Definition of done for M4

- [ ] `openapi.yaml` describes 100% of the `/v1` surface; Spectral clean; drift gate proven by watching it fail
- [ ] Every controller implements a generated interface
- [ ] A developer can create a workspace, mint an API key, build a question bank, create a tournament and play it **using only the public API**
- [ ] Idempotency, cursor pagination and rate limiting apply uniformly, not per-endpoint
- [ ] TypeScript SDK builds, type-checks and has a quickstart that has been run
- [ ] `CHANGELOG.md`, `STATUS.md`, plan checkboxes, issues and the project board updated per `CONTRIBUTING.md`
- [ ] ADR for the contract-first toolchain, recording why generated *interfaces* rather than generated controllers
- [ ] `v0.5.0` released — the first release whose version number means something to a consumer

## Owner actions required

Not implementable; they need an account or a decision only the owner can make.

- [ ] Decide the npm package name and create the npm organisation (`@quizforge` or similar). Publishing claims a public name permanently, so it should not happen as a side effect of a build.
- [ ] Enable GitHub Pages for the rendered spec.
- [ ] Confirm the free-tier rate limit number before it is documented — it is easy to lower privately and painful to lower publicly.

## Explicitly out of scope

- **Webhooks** — deferred to M4.5 with the outbox that `notify` will need anyway
- **Sandbox mode / `qf_test_` keys** — deferred; the prefix is reserved so no key needs reissuing
- **Python and Go SDKs** — after the pipeline is proven once
- **MFA/TOTP, OIDC/SAML** — spec defers SSO to the corporate segment
- **GraphQL** — rejected in §4.2 and still rejected
- **`/v2`** — additive-only within `/v1` for a long time yet

## Scope risk, stated plainly

This is the largest milestone since M1, and its riskiest property is that Tasks 3–5 are mostly *mechanical* — roughly 25 endpoints of validation, authorization and mapping. Mechanical work invites two failures: rushing it because each endpoint feels trivial, and discovering at endpoint 20 that the pagination or idempotency approach chosen at endpoint 3 does not fit.

Tasks 6 and 7 are sequenced **before** the bulk of the endpoints exist for that reason — the cross-cutting shape gets settled against a handful of endpoints rather than retrofitted across all of them. If the ordering has to change, change it deliberately and record why.

The second risk is the contract retrofit in Task 2. Making five existing controllers implement generated interfaces will surface mismatches between what the code does and what the contract says it does. Every one of those is a real finding. The temptation will be to edit the contract until it matches the code, which converts the exercise into a formality. The contract should change only where the *code* was right, and that judgement should be recorded per case.
