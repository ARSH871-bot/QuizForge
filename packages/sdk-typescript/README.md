# @quizforge/sdk

The TypeScript client for the QuizForge API.

Types are generated from [`openapi.yaml`](../../openapi.yaml), so they cannot
drift from the contract. Everything on top of them is hand-written, because
generated clients are unpleasant to use: this one gives you authentication,
automatic idempotency keys, async iteration that follows cursors, typed errors
carrying the stable error code, and backoff on `429` that honours `Retry-After`.

> **Not published.** The package name is not claimed on npm; that is the
> owner's decision, tracked as issue #83. Use it from this repository.

## What a key can do today

**Reads work. Writes do not.** Every write is audited against the account that
made it, and an API key names a workspace rather than a person, so the API
answers `403 PERMISSION_DENIED` to all of them. The write methods exist because
the contract defines them and a session-authenticated caller can reach them —
from a key they throw. Issue #98 is where that gets decided.

Playing — starting and submitting attempts — is absent from the SDK entirely.
An attempt belongs to a player, and there is no player behind a key.

## Quickstart

Bring the API up ([root README](../../README.md)), then:

```bash
cd packages/sdk-typescript
npm install
npm run build
```

`bootstrap.sh` creates an account, a workspace, a question bank with three
questions, and a key for you. It uses `curl` and a session cookie, because
everything it does is a write:

```bash
./examples/bootstrap.sh
```

```
Workspace: wsp_01a054877b1e73f1bd8b774347004864
Bank:      bnk_01a054877bb77268b7461a3ff22566c9
Import:    {"imported":3,"skipped":0,"failed":0,"failures":[]}

Now run the quickstart:

  export QUIZFORGE_API_KEY=qf_test_…
  npm run build && node examples/quickstart.ts
```

Then run [`examples/quickstart.ts`](examples/quickstart.ts):

```bash
export QUIZFORGE_API_KEY=qf_test_…
node examples/quickstart.ts
```

```
workspace:  Quickstart (ADMIN)
bank:       bnk_01a054877bb77268b7461a3ff22566c9  Geography
question:   SHORT_TEXT    Longest river in Africa?
              accepts Nile, The Nile
question:   SINGLE_CHOICE Capital of Japan?
              3 options
question:   SINGLE_CHOICE Capital of France?
              3 options
questions:  3, in pages of 2
member:     OWNER   quickstart-1788124559@example.test
write:      403 PERMISSION_DENIED — managing tournaments requires a signed-in account, not an API key
expected:   404 NOT_FOUND — question bank not found

quickstart complete.
```

## Using it

```ts
import { QuizForge, QuizForgeError } from "@quizforge/sdk";

const qf = new QuizForge({
  apiKey: process.env.QUIZFORGE_API_KEY,
  baseUrl: "http://localhost:8080",   // defaults to this
  maxRetries: 2,                      // attempts at most three times
});
```

### Iteration follows cursors

`list` returns an async generator, not a page. It requests `limit` items at a
time and follows `nextCursor` until there is none, so paging is not your
problem:

```ts
for await (const question of qf.questions.list(bankId, { limit: 100 })) {
  console.log(question.prompt);
}
```

The cursors are keyset cursors over `(created_at, id)`, so a question inserted
while you iterate neither duplicates an item nor skips one — the property
offset pagination fails.

### Payloads narrow exhaustively

A question's payload is a discriminated union. Narrowing on `kind` is
exhaustive: add a shape to the contract, regenerate, and TypeScript fails every
`switch` that does not handle it.

```ts
switch (payload.kind) {
  case "choice":    return payload.options.length;
  case "numeric":   return payload.value;
  case "shortText": return payload.accepted;
}
```

Five question types share three payload shapes — `SINGLE_CHOICE`, `TRUE_FALSE`
and `MULTI_CHOICE` are all `kind: "choice"`, and differ in how many options may
be correct. The type is carried on the question; the kind decides the fields.

### Errors carry a stable code

```ts
try {
  await qf.questionBanks.get(id);
} catch (error) {
  if (error instanceof QuizForgeError) {
    error.code;               // "NOT_FOUND" — stable, branch on this
    error.status;             // 404
    error.problem;            // the full RFC 9457 document
    error.retryAfterSeconds;  // set on a 429
    error.isRetryable;        // 429 and 5xx
  }
}
```

Branch on `code`, never on `message`: a code that exists will never change its
meaning or its status, while `detail` is written for people and gets reworded.

The API adds codes without a new version, so `ErrorCode` is every code
documented today plus any string. The known ones autocomplete and narrow; a
code this SDK has not heard of still type-checks, and should be handled by
`status`. `KNOWN_ERROR_CODES` carries each known code's status and meaning,
generated from the contract.

```ts
if (error instanceof QuizForgeError && error.code === "ATTEMPTS_EXHAUSTED") {
  // the player's best result stands — show it rather than an error
}
```
`QuizForgeConnectionError` is thrown separately when the API could not be
reached at all — there is no status to report and no problem document to read.

### Retries are safe by construction

Every mutating request carries an `Idempotency-Key`, generated per call. The
client's own retry after a timeout reuses it, so a request that arrived but
never answered is not performed twice. Pass your own when *your* code retries
across process restarts:

```ts
await qf.tournaments.create(draft, { idempotencyKey: `weekly-${week}` });
```

A `429` waits for `Retry-After` when the server sends one — it knows when the
bucket refills, and the client is only guessing — and otherwise backs off
exponentially with jitter.

## Development

```bash
npm run generate   # regenerate src/types.ts from ../../openapi.yaml
npm run typecheck
npm run build
```

`src/types.ts` is generated and committed. CI regenerates it and fails if the
result differs, so the checked-in types always match the contract.
