# 7. Keep the repository private; substitute the lost security controls

Date: 2026-08-12

## Status

Accepted

## Context

Branch protection, rulesets, GitHub secret scanning with push protection, and
CodeQL code scanning all require either a public repository or a paid GitHub
plan. This repository is private on the Free plan and the owner has decided it
stays private.

Accepting that decision without compensating would leave four real gaps in a
project whose stated goal is a launch-ready commercial product.

## Decision

The repository remains private. Each lost control is replaced by one that
works on any plan:

| Lost | Substitute | Where |
|---|---|---|
| Branch protection | `pre-push` hook rejecting direct pushes to `main` | `.githooks/pre-push` |
| Secret scanning + push protection | gitleaks on every pull request | CI job `secret-scan` |
| CodeQL | SpotBugs 4.8.6 with FindSecBugs 1.13.0, failing the build at Medium threshold | `spotbugs-maven-plugin`, bound to `verify` |
| Dependabot alerts | None needed — already enabled and works on private repositories | GitHub settings |

SpotBugs analyses only `com.quizforge.*`. The legacy `cs.quizzapp` package is
frozen and deleted in M3; analysing it would produce findings nobody intends
to act on, which trains readers to ignore the report.

The `codeql` CI job is retained but guarded so it skips on a private
repository. It activates automatically if visibility ever changes, at no cost
in the meantime.

## Consequences

Positive: every control has a working equivalent, and two of them (SpotBugs,
gitleaks) are portable — they would keep working after a migration away from
GitHub, which CodeQL would not.

Negative, and worth stating plainly: the `pre-push` hook is **advisory**. It is
bypassed by `--no-verify` and by any clone that has not run
`git config core.hooksPath .githooks`. Server-side branch protection cannot be
bypassed; this can. It catches accidents, not intent.

The SpotBugs bar was verified rather than assumed: a deliberately vulnerable
canary class was added, confirmed to produce `SQL_INJECTION_JDBC` and
`PREDICTABLE_RANDOM` findings, and removed. A scanner reporting zero findings
that has never been shown to detect anything is worse than no scanner, because
it manufactures false confidence.
