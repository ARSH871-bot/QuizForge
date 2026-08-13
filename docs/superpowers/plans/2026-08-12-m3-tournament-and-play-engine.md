# M3 — Tournament & Play Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tournaments that open for a window, an `Attempt` aggregate that makes play resumable, idempotent and cheat-resistant, leaderboards derived from graded attempts — and the retirement of the legacy package, which removes the last unauthenticated surface in the product.

**Architecture:** Populates the `tournament`, `play` and `leaderboard` modules. `Attempt` is the aggregate root for play: it freezes its question set at creation, persists one `Response` per answer as the player advances, grades server-side through M2's `GraderRegistry`, and enforces time limits against its own `startedAt`. Leaderboards are materialised on an `AttemptGraded` event rather than computed on read.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Modulith (application events), PostgreSQL 16, Flyway, Testcontainers, ArchUnit.

## Global Constraints

Everything in M1 and M2's constraints still applies. In addition:

- **Migrations start at V8.** V1–V7 exist. Never edit an applied migration.
- **Cross-module access goes through published APIs.** `play` needs questions
  and grading from `content`, and permissions from `identity`. Both must expose
  a root-package interface (as `identity.WorkspaceAccess` already does) rather
  than `content` widening `allowedDependencies` to its internals.
- **Correct answers never leave the server during play.** Every DTO returned
  mid-attempt must be asserted answer-free by a test, for every question type.
- **The legacy package is deleted, not disabled.** Half-deleted code is worse
  than either state: it still compiles, still confuses readers, and still
  answers requests.

## Prerequisites

```bash
cd apps/api && ./mvnw -B clean verify   # expect BUILD SUCCESS, 99 tests
docker compose ps                        # postgres and mailpit healthy
```

M0–M2 are merged. Issue #2 (credential revocation) remains open and does not
block this milestone.

---

## The irreversibility warning

**Task 7 deletes the legacy `cs.quizzapp` package.** Everything built so far
has been additive alongside it; this is the first step that removes working
behaviour.

What disappears: 41 endpoints under `/api/**`, including the entire current
quiz-playing experience. Until Tasks 1–6 are complete and verified, that legacy
code **is** the product's only working play path.

Two consequences to accept before starting:

1. **Task 7 must be last, and must not start until Tasks 1–6 are green.** The
   replacement has to exist before the original is removed.
2. **The legacy data is not migrated.** The old `legacy_question`, `quiz`,
   `score` and `participation` tables hold coursework data with no production
   value. Task 7 drops them. If that assumption is ever wrong — if real data
   lands in them first — Task 7 needs a migration step and this plan is wrong.

The upside is equally concrete: deleting `/api/**` removes the last
unauthenticated surface, which is the single blocker in `STATUS.md` preventing
this from being deployed anywhere reachable.

---

## Domain decisions taken

**1. `Attempt` freezes its question set at creation.** The rows chosen are
written to `attempt_question` with their order. A later revision of a question
creates a new row (M2's model), so a graded attempt can always be replayed
exactly as the player saw it.

**2. Responses are written as the player advances, not on submit.** One row per
answered question. This is what makes an attempt resumable after a disconnect
and what makes the one-question-per-page flow persist something — the two
defects the prototype could not fix without this model.

**3. Grading happens on write of each response, not on submit.** The result is
stored per response. Submission then aggregates rather than grades, which makes
it idempotent for free.

**4. Option order is shuffled per attempt, seeded by `attempt.id`.** Two
players get different orders, and a replay of the same attempt gets the same
order. Shuffling at import would have broken M2's content hash; this is where
it belongs.

**5. Leaderboards are materialised on `AttemptGraded`.** Computing on read is
simpler but re-reads every attempt for every viewer. A materialised standings
row per (tournament, account) is one upsert per graded attempt.

**6. Expiry is enforced lazily, on access, plus a scheduled sweep.** A time
limit checked only by a background job lets a player submit late if the job is
behind. Checking on every access makes lateness impossible; the sweep exists
only to close abandoned attempts so leaderboards settle.

---

## Task 1: Tournament schema and lifecycle

**Files:** `db/migration/V8__tournament.sql`, `tournament/domain/Tournament.java`,
`tournament/repo/TournamentRepository.java`, `tournament/app/TournamentService.java`,
`tournament/package-info.java`
**Test:** `tournament/app/TournamentServiceTest.java`

**Interfaces:**
- Consumes: M2's `question_bank`, M1's `WorkspaceAccess`
- Produces: `TournamentService.create(workspaceId, actorId, TournamentDraft) -> Tournament`; `open(id)`, `close(id)`; `Tournament.state()` returning `SCHEDULED | OPEN | CLOSED`; `maxAttempts` and `scoringPolicy` per ADR 0003.

Schema outline (`tournament` table): `id`, `workspace_id`, `bank_id`, `name`,
`slug`, `opens_at`, `closes_at`, `question_count`, `time_limit_seconds`,
`max_attempts` (default 1), `scoring_policy` (`BEST|FIRST|LAST|AVERAGE`,
default `FIRST`), `created_by`, timestamps, `version`. Constraints: closes
after opens; `question_count >= 1`; `max_attempts >= 1`; check constraints on
both enums. RLS policy identical to V5/V7, plus an explicit
`GRANT ... TO quizforge_app` — V5's defaults do not cover later tables.

Tests must cover: a tournament cannot close before it opens; a tournament
referencing a bank with fewer questions than `question_count` is rejected at
creation, not at play; `state()` is derived from the clock rather than stored,
so no scheduled job is needed to transition it.

---

## Task 2: The Attempt aggregate

**Files:** `db/migration/V9__attempt.sql`, `play/domain/{Attempt,Response,AttemptState}.java`,
`play/repo/*.java`, `play/package-info.java`
**Test:** `play/domain/AttemptTest.java`

**Interfaces:**
- Consumes: Task 1
- Produces: `Attempt` with states `STARTED → SUBMITTED → GRADED → EXPIRED`, `Attempt.answer(questionId, given, GradingResult)`, `Attempt.submit()`, `Attempt.isExpired(Instant)`.

`attempt`: `id`, `tournament_id`, `workspace_id`, `account_id`, `state`,
`started_at`, `submitted_at`, `graded_at`, `expires_at`, `score_numerator`,
`score_denominator`, `version`.

`attempt_question`: `attempt_id`, `question_id`, `position`, `option_order`
(the per-attempt shuffle, stored so a replay matches). Primary key
`(attempt_id, position)`.

`response`: `id`, `attempt_id`, `question_id`, `given`, `correct`,
`answered_at`. Unique on `(attempt_id, question_id)` — answering twice replaces
rather than duplicates.

The denominator is `question_count` frozen at creation. This is the fix for the
prototype defect where scores were graded against every question in the quiz
while players only ever saw ten.

Tests are pure domain, no Spring: state transitions reject illegal moves
(answering a `SUBMITTED` attempt, submitting twice), and `isExpired` is
computed from `startedAt` plus the tournament time limit rather than trusting
any client value.

---

## Task 3: Playing an attempt

**Files:** `play/app/AttemptService.java`, `play/web/dto/*.java`
**Test:** `play/app/AttemptServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1–2, `content`'s published question and grading API
- Produces: `AttemptService.start(tournamentId, accountId) -> Attempt`; `question(attemptId, position) -> QuestionView`; `answer(attemptId, position, given) -> AnswerFeedback`; `submit(attemptId) -> AttemptResult`.

`QuestionView` carries prompt, options in the attempt's shuffled order, position
and total — and **no correct answer**. A test asserts this for all five types by
serialising to JSON and asserting the correct answer string is absent.

`start` enforces `maxAttempts` and tournament state. `answer` enforces attempt
state and expiry, grades through `GraderRegistry`, and upserts the response.
`submit` aggregates stored results and publishes `AttemptGraded`.

Required tests: answering after expiry fails; submitting twice is idempotent and
returns the same result; an unanswered question counts as incorrect rather than
reducing the denominator; a second attempt is refused when `maxAttempts` is 1
and allowed when it is 2.

**`content` must publish an API for this.** Add
`com.quizforge.content.QuestionAccess` in content's root package exposing
`sample(bankId, count) -> List<QuestionRef>`, `view(questionId) -> QuestionView`
and `grade(questionId, given) -> GradingResult`. Do not widen `play`'s
`allowedDependencies` to `content.app` or `content.domain`.

---

## Task 4: Leaderboards

**Files:** `db/migration/V10__leaderboard.sql`, `leaderboard/domain/Standing.java`,
`leaderboard/app/LeaderboardService.java`, `leaderboard/package-info.java`
**Test:** `leaderboard/app/LeaderboardServiceTest.java`

**Interfaces:**
- Consumes: Task 3's `AttemptGraded` event
- Produces: `LeaderboardService.standings(tournamentId, limit) -> List<Standing>`.

`standing`: `tournament_id`, `account_id`, `best_score`, `attempts`,
`first_graded_at`, primary key `(tournament_id, account_id)`. The listener
upserts according to the tournament's `scoringPolicy`.

Ties break by `first_graded_at` ascending — the player who got there first
ranks higher. Tests must cover each `scoringPolicy` and the tie-break, and must
assert the listener is idempotent: replaying the same `AttemptGraded` twice
must not double-count.

---

## Task 5: Expiry sweep

**Files:** `play/app/AttemptExpiryJob.java`
**Test:** `play/app/AttemptExpiryJobTest.java`

A `@Scheduled` sweep moving `STARTED` attempts past `expires_at` to `EXPIRED`,
grading whatever responses exist. Runs every minute; uses `ShedLock` or a
Postgres advisory lock so two instances do not both sweep.

Expiry is already enforced on access (decision 6), so this job only closes
abandoned attempts. Its test asserts the job is a no-op for attempts inside
their window, and that an expired attempt still produces standings.

---

## Task 6: Play endpoints

**Files:** `play/web/AttemptController.java`, `tournament/web/TournamentController.java`
**Test:** `play/web/AttemptControllerTest.java`

`POST /v1/tournaments/{id}/attempts`, `GET /v1/attempts/{id}/questions/{position}`,
`POST /v1/attempts/{id}/questions/{position}/answer`, `POST /v1/attempts/{id}/submit`,
`GET /v1/tournaments/{id}/standings`.

All require authentication; all are workspace-scoped through `TenantContext`.
A test asserts an account from another workspace receives 404 rather than 403 —
existence itself is not disclosed across tenants.

---

## Task 7: Retire the legacy package

> **Do not start until Tasks 1–6 are complete, merged, and green.** This removes
> the product's only currently-working play path.

**Files:** delete `apps/api/src/main/java/cs/quizzapp/**` and its tests;
`db/migration/V11__drop_legacy.sql`; modify `QuizForgeApplication`,
`SecurityConfig`, `ArchitectureTest`, `AuthenticationTest`.

- [ ] **Step 1: Confirm the replacement is real**

Every legacy capability must have a `/v1` equivalent with a passing test. Walk
the 41 legacy endpoints and tick each one off against its replacement, or
explicitly record it as intentionally dropped. Do not proceed on the assumption
that the mapping is complete.

- [ ] **Step 2: Delete the Java**

```bash
git rm -r apps/api/src/main/java/cs/quizzapp
```

- [ ] **Step 3: Simplify what only existed to accommodate it**

- `QuizForgeApplication`: drop the `cs.quizzapp` entries from `@ComponentScan`,
  `@EntityScan` and `@EnableJpaRepositories` — the explicit declarations become
  unnecessary once the entry point sits above every remaining package.
- `SecurityConfig`: delete the `/api/**` permitAll rule. This is the change
  that closes the last unauthenticated surface.
- `content.app.QuestionService`: drop the `"contentQuestionService"` qualifier.
- `AuthController`: drop the `"identityAuthController"` qualifier.
- `ArchitectureTest`: delete `newCodeMustNotDependOnLegacyCode` and the
  `cs.quizzapp` import — a rule guarding a package that no longer exists is
  noise that will confuse the next reader.
- `AuthenticationTest`: delete `leavesTheLegacyApiOpenUntilM3`.

- [ ] **Step 4: Drop the legacy tables**

`V11__drop_legacy.sql` drops `question_options`, `legacy_question`, `score`,
`participation`, `quiz_likes`, `categories`, `quiz`, `users` — in that order,
children before parents.

Note `users` is the legacy table; M1's accounts live in `account`. Confirm with
`SELECT count(*) FROM users` that nothing of value is there before dropping.

- [ ] **Step 5: Verify the surface is closed**

```bash
cd apps/api && ./mvnw -B verify
```

Then assert positively rather than assuming: a test that `GET /api/questions`
returns 404 (route gone), and that every `/v1/**` route still requires
authentication.

- [ ] **Step 6: Update the artefacts**

`STATUS.md`'s "What is safe to do with this code today" section must change —
the legacy blocker is the only remaining reason it says do not deploy. Removing
it is the point of this task, and leaving that section stale would misrepresent
the product's safety in exactly the direction that matters.

---

## Definition of done for M3

- [ ] `./mvnw verify` passes from a clean clone
- [ ] An attempt can be started, answered question by question, resumed after a simulated disconnect, and submitted
- [ ] Submitting twice returns the same result and does not double-count
- [ ] No response mid-attempt contains a correct answer, asserted for all five types
- [ ] Score denominator is the frozen question count, not the bank size
- [ ] Expiry is enforced on access, not only by the sweep
- [ ] Standings are correct for each scoring policy, and the listener is idempotent
- [ ] `cs.quizzapp` does not exist and `/api/**` returns 404
- [ ] `STATUS.md` no longer lists the legacy package as a deployment blocker
- [ ] `CHANGELOG.md`, plan checkboxes and issues are current

## Explicitly out of scope

HTTP endpoints for content and workspace management (still M4, generated from
the OpenAPI contract). Live synchronous play. Partial credit for multi-choice.
Question-level analytics. Anti-cheat beyond server-side timing and per-attempt
shuffling — device fingerprinting and proctoring are a separate product
decision, not an implementation detail.

## Scope risk, stated plainly

M3 is the largest milestone in the plan: three modules, four migrations, and a
deletion. If it needs splitting, the natural seam is **Tasks 1–3 (play works)
and Tasks 4–7 (leaderboards and retirement)**, shipped as two milestones. That
is preferable to letting one branch run for weeks.
