# Getting started with the QuizForge API

From an empty machine to a played tournament, in one sitting. Every command
here was run against a clean database in this order, and the responses are the
real ones — identifiers will differ, nothing else will.

At the end you will have an account, a workspace, a question bank, a
tournament, a played attempt and a leaderboard, without touching a database
console or a web page.

If you would rather run it than read it, [`quickstart.sh`](quickstart.sh) is
this document as a script.

## Contents

- [Before you start](#before-you-start)
- [Two kinds of credential](#two-kinds-of-credential)
- [1. Create an account](#1-create-an-account)
- [2. Sign in](#2-sign-in)
- [3. Create a workspace](#3-create-a-workspace)
- [4. Create a question bank](#4-create-a-question-bank)
- [5. Import questions](#5-import-questions)
- [6. Write one by hand](#6-write-one-by-hand)
- [7. Mint an API key](#7-mint-an-api-key)
- [8. Schedule a tournament](#8-schedule-a-tournament)
- [9. Play it](#9-play-it)
- [10. Read the standings](#10-read-the-standings)
- [Things that apply everywhere](#things-that-apply-everywhere)
- [Where to go next](#where-to-go-next)

## Before you start

You need Java 21, Docker and Git. Nothing else — no cloud account, no API key
from anyone, no email provider.

```bash
git clone https://github.com/ARSH871-bot/QuizForge.git && cd QuizForge
cp .env.example .env
docker compose up -d                       # PostgreSQL 16 and a local mailbox
cd apps/api && DB_PASSWORD=local-dev-only ./mvnw spring-boot:run
```

The first run downloads dependencies and applies the migrations; give it a
minute. In another terminal:

```bash
export QF=http://localhost:8080
curl -s $QF/v1/auth/health
```

```json
{"status":"ok"}
```

`$QF` is used for the rest of this document. Everything below is a real HTTP
call to a real server; there is no mock mode and no seeded demo data.

## Two kinds of credential

QuizForge accepts two, and the difference decides which headers you send.

|  | Session cookie | API key |
|---|---|---|
| Obtained by | `POST /v1/auth/login` | `POST /v1/api-keys` |
| Sent as | `Cookie: qf_session=…` | `Authorization: Bearer qf_…` |
| Identifies | a person | a workspace |
| Workspace | say which one: `X-QuizForge-Workspace` | already known from the key |
| CSRF | writes need `X-XSRF-TOKEN` | not applicable |
| Can write | yes | **no — see [step 7](#7-mint-an-api-key)** |

A browser client uses the cookie. A server-to-server client uses a key. This
walkthrough uses the cookie, because most of it is writing.

Two consequences of using a cookie, both visible in the commands below:

- **`X-XSRF-TOKEN` on every write.** The token arrives as an `XSRF-TOKEN`
  cookie on any response; echo its value back in the header. `curl -c jar -b
  jar` keeps the cookie jar, and the `csrf` helper reads the token out of it.
- **`X-QuizForge-Workspace` on everything outside `/v1/auth`.** An account can
  belong to several workspaces, so it says which one it means. Omitting it is
  `400 INVALID_REQUEST`, not a guess.

Set up the jar and the helper once:

```bash
JAR=$(mktemp)
curl -s -c $JAR -o /dev/null $QF/v1/auth/health
csrf() { grep XSRF-TOKEN $JAR | awk '{print $7}'; }
```

## 1. Create an account

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/auth/register \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"email":"ada@example.com","password":"correct horse battery staple","displayName":"Ada"}'
```

```json
{"id":"acc_01a05499b0bb7a9eadf5064a6d65ea0e","email":"ada@example.com","displayName":"Ada","emailVerified":false}
```

Registering does **not** sign you in — no session cookie is issued. That is
deliberate: creating an account and starting a session are separate actions,
and conflating them makes it impossible to create an account on someone's
behalf without also becoming them.

Identifiers are prefixed (`acc_`, `wsp_`, `bnk_`, `qst_`, `trn_`, `att_`,
`key_`). The prefix is part of the value, so an identifier pasted into the
wrong endpoint is rejected as malformed rather than looked up and not found.

## 2. Sign in

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"email":"ada@example.com","password":"correct horse battery staple"}'
```

```json
{"id":"acc_01a05499b0bb7a9eadf5064a6d65ea0e","email":"ada@example.com","displayName":"Ada","emailVerified":false}
```

The session cookie is now in `$JAR`. An unknown email and a wrong password both
answer `INVALID_CREDENTIALS`, and the work done is equalised so the two cannot
be told apart by timing either.

## 3. Create a workspace

A new account belongs to no workspace, and almost nothing is reachable without
one. This is the one write that needs no workspace header — requiring one would
make it unreachable.

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/workspaces \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"name":"Ada Quizzes"}'
```

```json
{"id":"wsp_01a05499b25d7c2bac3e49e29b01b71d","name":"Ada Quizzes","slug":"ada-quizzes","role":"OWNER"}
```

Keep the id — every remaining call carries it:

```bash
WSP=wsp_01a05499b25d7c2bac3e49e29b01b71d   # yours will differ
```

The slug is derived from the name and made unique. It is not accepted from the
request, so nobody can squat on one.

You are the `OWNER`. Roles are `OWNER`, `ADMIN`, `EDITOR` and `VIEWER`, and the
last `OWNER` can be neither demoted nor removed — a workspace nobody can
administer is a support ticket, not a feature.

## 4. Create a question bank

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/question-banks \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -H "X-QuizForge-Workspace: $WSP" \
  -d '{"name":"Geography","description":"Capitals and rivers."}'
```

```json
{"id":"bnk_01a05499b3867aa6ab48bde6a2706dd8","name":"Geography","description":"Capitals and rivers."}
```

```bash
BANK=bnk_01a05499b3867aa6ab48bde6a2706dd8   # yours will differ
```

A bank is a collection of questions a tournament draws from. Deleting one
archives it rather than removing it, so a tournament played last month stays
explicable.

## 5. Import questions

The CSV import takes the document itself — note `Content-Type: text/csv`, not
JSON wrapping a string. The header line is required and ignored.

The third row below is deliberately broken: a `NUMERIC` question whose answer
is the word "seven".

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/question-banks/$BANK/imports/csv \
  -H "Content-Type: text/csv" -H "X-XSRF-TOKEN: $(csrf)" \
  -H "X-QuizForge-Workspace: $WSP" \
  --data-binary $'type,prompt,options,correct,difficulty\nSINGLE_CHOICE,Capital of France?,Paris|Lyon|Nice,Paris,EASY\nSINGLE_CHOICE,Capital of Japan?,Tokyo|Osaka|Kyoto,Tokyo,EASY\nNUMERIC,How many continents?,,seven,EASY\nSHORT_TEXT,Longest river in Africa?,,Nile|The Nile,HARD\n'
```

```json
{"imported":3,"skipped":0,"failed":1,"failures":[{"line":4,"message":"'seven' is not a number"}]}
```

**`200` means the file was processed, not that every row succeeded.** Check the
report. One bad row does not discard the rest, and each failure carries the
1-indexed line number *including the header* — line 4 is the fourth line of the
file, which is what a spreadsheet shows. The line is a field, not prose inside
the message, so you can act on it.

Re-importing the same file counts duplicates as `skipped` rather than failing,
so retrying an upload after a partial failure does the right thing.

`POST /v1/question-banks/{bankId}/imports/opentdb` pulls from the Open Trivia
Database instead, and reports the same way.

## 6. Write one by hand

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/question-banks/$BANK/questions \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -H "X-QuizForge-Workspace: $WSP" \
  -d '{"type":"TRUE_FALSE","prompt":"Canberra is the capital of Australia.","difficulty":"EASY","payload":{"kind":"choice","options":[{"text":"True","correct":true},{"text":"False","correct":false}]}}'
```

```json
{"id":"qst_01a05499b58e7b2887df149d28d8f4b0","bankId":"bnk_01a05499b3867aa6ab48bde6a2706dd8","lineageId":"qst_01a05499b58e7b2887df149d28d8f4b0","version":1,"type":"TRUE_FALSE","prompt":"Canberra is the capital of Australia.","payload":{"kind":"choice","options":[{"text":"True","correct":true},{"text":"False","correct":false}]},"difficulty":"EASY","retired":false}
```

Five question types share three payload shapes. The `type` decides the rules;
the `kind` decides the fields:

| `type` | `kind` | Rule |
|---|---|---|
| `SINGLE_CHOICE` | `choice` | at least two options, exactly one correct |
| `TRUE_FALSE` | `choice` | exactly two options, exactly one correct |
| `MULTI_CHOICE` | `choice` | at least two options, at least one correct |
| `NUMERIC` | `numeric` | a `value` and a `tolerance` of zero or more |
| `SHORT_TEXT` | `shortText` | at least one `accepted` answer, none blank |

Discriminating on the shape rather than the type gives a typed client three
branches that differ instead of five where three are identical.

**Questions are immutable.** `PATCH /v1/questions/{id}` does not edit one: it
writes a new version and supersedes the old, which keeps its own `id` and stays
byte-identical forever. `lineageId` is what the versions share, and
`GET /v1/questions/{id}/versions` lists them. An attempt played in March scored
against version 1 can still be explained in December.

## 7. Mint an API key

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/api-keys \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -H "X-QuizForge-Workspace: $WSP" \
  -d '{"name":"laptop","environment":"test"}'
```

```json
{"id":"key_01a05499b6927d83bc92dabddd8cabd4","name":"laptop","environment":"test","lastFour":"a1B2","createdAt":"2026-08-30T21:35:55.026081Z","secret":"qf_test_example_value_not_a_real_key_a1B2"}
```

The `secret` above is the one value in this document that is *not* the real
response — a working key does not belong in a repository, and the secret
scanner agrees. A real one is `qf_` plus the environment plus 43 random
characters.

**`secret` is shown exactly once.** Only a SHA-256 digest is stored, so nothing
can show it to you again — not this API, not the database, not the person who
runs the server. Copy it now.

A key needs no cookie, no CSRF token and no workspace header. It names one
workspace, and that is the one it gets:

```bash
KEY=qf_test_…   # yours will differ
curl -s $QF/v1/question-banks -H "Authorization: Bearer $KEY"
```

```json
{"data":[{"id":"bnk_01a05499b3867aa6ab48bde6a2706dd8","name":"Geography","description":"Capitals and rivers."}]}
```

### What a key cannot do

```bash
curl -s -X POST $QF/v1/question-banks -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" -d '{"name":"By key"}'
```

```json
{"type":"https://quizforge.dev/errors/permission-denied","title":"PERMISSION_DENIED","status":403,"detail":"authoring requires a signed-in account, not an API key","instance":"/v1/question-banks","code":"PERMISSION_DENIED"}
```

**A key reads. It does not write.** Every write is audited against the account
that made it, and a key names a workspace rather than a person, so there is
nobody to record. Whether that should change, and what a key-authored write
would be attributed to, is
[issue #98](https://github.com/ARSH871-bot/QuizForge/issues/98).

A key also cannot mint or revoke another key. That one is permanent: it means a
leaked credential cannot extend its own foothold or outlive the key that made
it. Revocation takes effect on the very next request.

## 8. Schedule a tournament

Back to the session cookie, since this is a write. `opensAt` in the past starts
it immediately.

```bash
DRAFT='{"name":"Geography Weekly","bankId":"'$BANK'","opensAt":"2026-08-30T21:34:55Z","closesAt":"2026-09-06T21:35:55Z","questions":3,"timeLimitSeconds":600,"maxAttempts":2,"scoringPolicy":"BEST"}'

curl -s -b $JAR -c $JAR -X POST $QF/v1/tournaments \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -H "X-QuizForge-Workspace: $WSP" -H "Idempotency-Key: weekly-2026-w36" \
  -d "$DRAFT"
```

```json
{"id":"trn_01a05499b902799dba7ef4e7e8dfca9f","name":"Geography Weekly","state":"OPEN","opensAt":"2026-08-30T21:34:55Z","closesAt":"2026-09-06T21:35:55Z","questions":3,"timeLimitSeconds":600,"maxAttempts":2,"bankId":"bnk_01a05499b3867aa6ab48bde6a2706dd8","scoringPolicy":"BEST"}
```

```bash
TRN=trn_01a05499b902799dba7ef4e7e8dfca9f   # yours will differ
```

`state` is computed from the window and the clock, never stored, so a
tournament goes `SCHEDULED` → `OPEN` → `CLOSED` with no job running and no row
that can disagree with the calendar. Amending or deleting one is refused once
it is open: its players started under its rules.

`questions: 3` is how many each attempt draws, and is refused if the bank holds
fewer. `scoringPolicy` is applied when standings are read rather than when a
score is stored, so changing it takes effect without recomputing history.

### The idempotency key

Send the identical request again with the same `Idempotency-Key`:

```bash
curl -s -D - -o /dev/null -b $JAR -c $JAR -X POST $QF/v1/tournaments \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -H "X-QuizForge-Workspace: $WSP" -H "Idempotency-Key: weekly-2026-w36" \
  -d "$DRAFT" | grep -i "^HTTP/\|^idempotency-replayed"
```

```
HTTP/1.1 201
Idempotency-Replayed: true
```

You get the original response back, and there is still one tournament. The key
is remembered for 24 hours per workspace. Reusing it with a *different* body is
`422 IDEMPOTENCY_KEY_REUSED` rather than silently doing something else.

Every mutating `/v1` request accepts the header. Use it whenever a retry could
otherwise repeat the work.

## 9. Play it

Playing is the one thing an API key can never do: an attempt belongs to a
player, and a key has no player behind it.

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/tournaments/$TRN/attempts \
  -H "X-XSRF-TOKEN: $(csrf)" -H "X-QuizForge-Workspace: $WSP"
```

```json
{"id":"att_01a05499ba767f15af753b0e8e65de06","questions":3,"expiresAt":"2026-08-30T21:45:56.0194197Z"}
```

```bash
ATT=att_01a05499ba767f15af753b0e8e65de06   # yours will differ
```

The attempt froze its three questions and its deadline at this moment, so a
later change to the bank cannot alter its denominator. Fetch a question by
position:

```bash
curl -s -b $JAR $QF/v1/attempts/$ATT/questions/1 -H "X-QuizForge-Workspace: $WSP"
```

```json
{"questionId":"qst_01a05499b4ad7648bce289c1671fb814","type":"SINGLE_CHOICE","prompt":"Capital of France?","options":["Nice","Paris","Lyon"]}
```

**No payload, and no correct answer.** A playable question never carries one,
for any type, at any point during the attempt — including after it is answered.
The option order is this attempt's own shuffle, remembered so a refresh does not
reorder them.

Answer it. The answer is always a string, whatever the type: for
`MULTI_CHOICE`, the selected options joined by `|`; for `NUMERIC`, the number as
text, compared within the question's tolerance.

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/attempts/$ATT/questions/1/answer \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf)" \
  -H "X-QuizForge-Workspace: $WSP" -d '{"answer":"Nice"}'
```

```json
{"position":1,"correct":false,"answered":1,"total":3,"hasNext":true}
```

Repeat for positions 2 and 3, then submit:

```bash
curl -s -b $JAR -c $JAR -X POST $QF/v1/attempts/$ATT/submit \
  -H "X-XSRF-TOKEN: $(csrf)" -H "X-QuizForge-Workspace: $WSP"
```

```json
{"attemptId":"att_01a05499ba767f15af753b0e8e65de06","state":"GRADED","score":1,"outOf":3,"percentage":33.333333333333336}
```

An attempt that is never submitted expires on its own at `expiresAt` and is
graded on what was answered. Nothing is lost by closing the tab.

## 10. Read the standings

```bash
curl -s -b $JAR $QF/v1/tournaments/$TRN/standings -H "X-QuizForge-Workspace: $WSP"
```

```json
{"data":[{"accountId":"acc_01a05499b0bb7a9eadf5064a6d65ea0e","score":1.0,"outOf":3,"attempts":1,"firstGradedAt":"2026-08-30T21:35:57.226401Z","rank":1}]}
```

Ranking is resolved when the standings are read. `firstGradedAt` breaks ties,
so the player who got there first is ahead — and a tie is never broken
arbitrarily or by identifier.

That is the whole loop. Everything above ran against a database that was empty
ten minutes ago.

## Things that apply everywhere

### Pagination

Every list returns `{ "data": [...], "nextCursor": "…" }`. Pass `nextCursor`
back as `?cursor=`; when it is absent or `null`, you have everything.

```bash
curl -s -b $JAR "$QF/v1/question-banks/$BANK/questions?limit=2" \
  -H "X-QuizForge-Workspace: $WSP"
```

```json
{"data":[{"id":"qst_01a05499b58e7b2887df149d28d8f4b0","…":"…"}],"nextCursor":"djE6MDFhMDU0OTktYjRjYi03Y2Y4LWE0MDMtOTdmMjRhMWVjNDA0"}
```

The cursor is opaque — do not parse it or construct one. It is a keyset cursor,
so a row inserted while you page neither duplicates an item nor skips one,
which is the property offset pagination fails. `limit` is 1–100 and defaults to
25; out of range is rejected rather than quietly clamped. A malformed or
foreign cursor is `400 INVALID_CURSOR`, not a `500`.

### Rate limits

Every response carries your budget, not only the ones that refuse you:

```bash
curl -s -D - -o /dev/null $QF/v1/question-banks -H "Authorization: Bearer $KEY" | grep -i '^ratelimit'
```

```
RateLimit-Limit: 120
RateLimit-Remaining: 117
RateLimit-Reset: 0
```

`RateLimit-Reset` is seconds until at least one request is available, so `0`
means one already is. Over the limit is `429 RATE_LIMITED` with `Retry-After` —
wait that long rather than guessing. Limits follow the **credential**, so two
keys in one workspace do not share an allowance.

### Errors

Every error is [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem
details with one extra field:

```json
{"type":"https://quizforge.dev/errors/permission-denied","title":"PERMISSION_DENIED","status":403,"detail":"authoring requires a signed-in account, not an API key","instance":"/v1/question-banks","code":"PERMISSION_DENIED"}
```

**Branch on `code`.** It is a stable enum: a code that exists will never change
its meaning or its HTTP status. `detail` is written for people and gets
reworded. The full list is `ErrorCode` in
[`openapi.yaml`](../../openapi.yaml).

Two worth knowing early: naming a workspace you are not a member of is `403
PERMISSION_DENIED`, not `404` — the credential is valid, the workspace is not
yours. And an identifier from another workspace is `404 NOT_FOUND`, because
telling you it exists elsewhere would leak that it exists.

## Where to go next

- **The TypeScript SDK**, [`packages/sdk-typescript`](../../packages/sdk-typescript) —
  the same API with cursors followed for you, idempotency keys generated per
  call, typed errors and `429` backoff. Its quickstart is a script you can run.
- **The full contract**, [`openapi.yaml`](../../openapi.yaml) — every endpoint,
  every field, every error code. Render it locally with `npm run spec` from the
  repository root, which serves it at <http://localhost:8090>.
- **[`STATUS.md`](../../STATUS.md)** — what works, what does not, and what is
  deliberately missing. It is kept honest rather than flattering.
