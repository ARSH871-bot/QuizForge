# 7. Keep the repository private; substitute the lost security controls

Date: 2026-08-12

## Status

Superseded by [ADR 0009](0009-public-repository-and-native-security-controls.md)
on 2026-08-17. The repository is now public and each substitute below has been
replaced by, or demoted beneath, the native control it stood in for. The
reasoning is kept because the substitutes were not all discarded — gitleaks and
SpotBugs are still in use, for reasons ADR 0009 records.

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
| Dependency updates | Dependabot alerts, security updates, and version updates | `.github/dependabot.yml` |

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

**Amendment, 2026-08-12.** This ADR originally specified Renovate for
dependency updates. Renovate is a GitHub App requiring a separate install step
that was never performed, so `renovate.json` sat inert for three milestones
while nothing updated dependencies. The gap was invisible because the file
existed and looked configured.

It surfaced during a repository audit: a **CRITICAL** advisory against
`bcprov-jdk18on` 1.78.1 (added in M1 for Argon2id) had an open Dependabot
security PR that nobody had noticed — and which was **blocked by this
repository's own `conventional-title` CI check**, because Dependabot's default
title is not a Conventional Commit.

Replaced with Dependabot, which needs no install and was already producing
security PRs, configured with `commit-message.prefix` so its titles pass the
check. Actual exposure from the advisory was nil — BouncyCastle is used only as
the Argon2 provider, and the vulnerabilities are in GOST ciphers and LDAP
handling, neither of which this codebase touches — but a security update that
CI silently blocks is a process failure regardless of the payload.

Lesson recorded because it generalises: **a configuration file is not a
control.** Verify the tool actually runs.

Negative, and worth stating plainly: the `pre-push` hook is **advisory**. It is
bypassed by `--no-verify` and by any clone that has not run
`git config core.hooksPath .githooks`. Server-side branch protection cannot be
bypassed; this can. It catches accidents, not intent.

The SpotBugs bar was verified rather than assumed: a deliberately vulnerable
canary class was added, confirmed to produce `SQL_INJECTION_JDBC` and
`PREDICTABLE_RANDOM` findings, and removed. A scanner reporting zero findings
that has never been shown to detect anything is worse than no scanner, because
it manufactures false confidence.
