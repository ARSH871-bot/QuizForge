# Contributing

## Workflow

Trunk-based. Branch from `main`, keep branches short-lived, open a pull
request. `main` is protected: linear history and passing status checks are
required.

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
