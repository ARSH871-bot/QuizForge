# Contributing

## Definition of done for any change

Code that works is half a change. A change is done when the repository still
tells the truth about itself. Before opening a pull request, walk this list —
every item that applies, every time:

| Artefact | Update when |
|---|---|
| `CHANGELOG.md` | Always. A change not worth a line here is not worth shipping. |
| `STATUS.md` | A milestone moves, a gap opens or closes, or what is safe to do with the code changes. |
| Plan checkboxes | A step is finished. Tick it in `docs/superpowers/plans/`. |
| The GitHub issue | Close it with the verification output, or comment what changed and why. |
| Project board | Set Estimate and Risk on new issues. Status is automated; do not set it by hand. |
| `docs/adr/` | A structural or security decision was made, including deciding *not* to do something. |
| The plan document | Reality diverged from the plan. Correct the plan; do not leave it lying. |
| `README.md` | A documented command changed. Run every command in it before claiming so. |
| `openapi.yaml` | The public API surface changed (from M4). |

The rule behind the table: **anything that would mislead a reader who trusts
it must be corrected in the same commit as the change that made it wrong.** A
stale document is worse than a missing one, because it is believed.

### Why this is written down

Two milestones were completed with every GitHub issue diligently closed and
150 plan checkboxes left unticked, no changelog, and nothing in the repository
recording progress. The work was done; the repository said otherwise. Tracking
that depends on remembering will be forgotten.

## Workflow

Trunk-based. Branch from `main`, keep branches short-lived, open a pull
request.

**`main` has no server-side protection.** Rulesets require a public repository
or paid Advanced Security, and this one is private on the Free plan (ADR 0013).
Nothing on GitHub will stop a direct push, a force-push, or a merge with red
checks. What exists instead is `.githooks/pre-push`, which is local, advisory,
and only active once `core.hooksPath` is set:

```bash
git config core.hooksPath .githooks    # first thing after cloning
```

The CI jobs still run on every pull request and are the real gate — they are
just not *enforced* as required checks. Read them before merging; nothing else
will.

```bash
git switch -c feat/short-description
# ... work ...
cd apps/api && ./mvnw verify     # must pass before pushing
git push -u origin feat/short-description
```

## First-time setup

Enable the commit message hook once per clone:

```bash
git config core.hooksPath .githooks
```

## Attribution

Commits carry no co-authorship or tooling trailers. Repository content,
commit messages, pull request descriptions, documentation, and product copy
reference no authoring tool of any kind. Verify with:

```bash
git log --format='%(trailers:key=Co-Authored-By)' | grep . && echo "REMOVE THESE"
```

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/), subject line
72 characters or fewer:

```
feat: add attempt expiry job
fix: correct score denominator for partial attempts
docs: document webhook signature verification
test: cover leaderboard tie-breaking
build: upgrade to spring boot 3.5.7
ci: cache maven dependencies
refactor: extract grading policy from attempt service
chore: untrack generated files
```

The `.githooks/commit-msg` hook rejects anything else. Because a local hook
can be bypassed with `--no-verify`, the same convention is enforced on pull
request titles by CI, where it cannot be skipped.

## Signing commits

```bash
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub
git config --global commit.gpgsign true
```

Add the same public key to GitHub under Settings → SSH and GPG keys, as a
**signing** key.

## Versioning and releases

Milestones are tagged `vMAJOR.MINOR.PATCH` and published as GitHub Releases
with notes written by hand, because generated notes describe commits rather
than consequences.

Semantic versioning of the public API starts at M4, when the OpenAPI contract
and SDKs exist and the number means something to a consumer. Before then the
numbers track milestones and serve as restore points.

The `CHANGELOG.md` is maintained by hand rather than generated. It carries the
reasoning behind decisions, which cannot be derived from commit messages.

### Cutting a release

Work accumulates under `## [Unreleased]`. Releasing means moving it into a
version section — **in the same pull request that tags the release**, not
afterwards:

1. Rename `## [Unreleased]` to `## [X.Y.0] — YYYY-MM-DD` and add a one-line
   note naming the milestone.
2. Merge duplicate subsection headings. Keep a Changelog allows one `Added`,
   one `Changed`, one `Removed`, one `Security` per version. Prepending a new
   heading instead of merging into the existing one is how a reader ends up
   finding the first `Security` block and stopping.
3. Add a fresh empty `## [Unreleased]` at the top reading `Nothing yet.`
4. Add the comparison link at the foot of the file:
   `[X.Y.0]: https://github.com/ARSH871-bot/QuizForge/compare/v(previous)...vX.Y.0`
   and repoint `[Unreleased]` at `vX.Y.0...HEAD`.
5. Merge, then tag the merge commit and publish the release with notes written
   by hand.

Anything that is a *current* limitation belongs in `STATUS.md`, not here. A
changelog records what happened in a version; a limitation that outlives the
version is not a changelog entry. The exception is a `Known issues` block
recording something a released version genuinely shipped with — that stays,
because it was true of that release forever after.

**Why this is spelled out:** it was not, and so it never happened. Releases
`v0.1.0` through `v0.3.0` were tagged while every entry stayed under
`[Unreleased]`, leaving a changelog that could not answer what changed in the
version you are running. The file was only rolled into version sections at
`0.4.0`, by checking each claim against the tagged trees. A step nobody wrote
down is a step nobody takes.

## Architecture decisions

Anything structural gets an ADR in `docs/adr/`, numbered sequentially, using
the existing files as a template. Record the context, the options considered,
the decision, and the consequences — especially the negative ones.

## Tests

Integration tests extend `AbstractIntegrationTest`, which provides a real
PostgreSQL container. Do not mock the database.

Module boundaries are enforced by `ModularityTest`. If it fails, the fix is
almost always to publish an event rather than to widen `allowedDependencies`.

Schema changes are Flyway migrations in
`apps/api/src/main/resources/db/migration`. Never change `ddl-auto` away from
`validate`.

## Concurrent tooling

Do not run automated code-modification tools (IDE migration assistants, agent
extensions) against this repository while working in it. They have previously
rewritten the POM, deleted migrations, and injected conflicting
configuration — see ADR 0006.
