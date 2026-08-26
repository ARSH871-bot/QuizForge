# 13. Keep the repository private, and say so plainly

Date: 2026-08-22

## Status

Accepted. Supersedes [ADR 0009](0009-public-repository-and-native-security-controls.md),
which in turn superseded [ADR 0007](0007-private-repository-security-substitutes.md).

ADR 0007's substitutes are in force again. They were never removed.

## Context

ADR 0009 made the repository public and adopted GitHub's native controls: a
ruleset protecting `main`, secret scanning with push protection, CodeQL, and
private vulnerability reporting. All of it was verified working at the time —
the ruleset by watching it reject a direct push with `GH013`.

The repository is private again. On the Free plan that turns off every one of
those controls: rulesets return `403`, and code scanning, secret scanning and
private vulnerability reporting all require a public repository or paid
Advanced Security.

The owner's reason is to stop others building on this work for their own
benefit. That is the decisive input and it settles the question: no arrangement
of security controls is worth publishing a pre-launch commercial codebase the
owner does not want published. Every control below is a consequence of that
decision, not a competing consideration.

Worth separating two things that sound alike. Private controls **access** —
nobody can read the code, which is the protection actually being asked for. The
proprietary `LICENSE` controls **use** — it is what would matter if the code
ever became readable, deliberately or otherwise. The licence has been in place
since M3 and stays; it is not an alternative to being private, and being private
is not an alternative to it.

What was not reasonable was `STATUS.md` continuing to describe the native
controls as active after they stopped being so.

**It surfaced through a merge, not through a settings page.** Pull request #88
merged while `CodeQL` was a required status check that never reported. A
required check whose producer is disabled does not block a merge; it simply
never appears, and GitHub does not wait for something that is not coming. The
protection read as configured and enforced nothing.

## Decision

The repository stays private. ADR 0009 is superseded rather than amended,
because its central premise — that the repository is public — is false.

**`main` has no server-side protection.** Not weakened, not advisory-with-a-net:
absent. Nothing on GitHub's side prevents a direct push to `main`.

What remains, and it is not nothing:

| Control | Mechanism | Enforced where |
|---|---|---|
| Build, tests, SpotBugs + FindSecBugs | CI job `build` | GitHub Actions |
| Secret scanning | gitleaks, CI job `secret-scan` | GitHub Actions |
| Contract lint | Spectral, CI job `openapi-lint` | GitHub Actions |
| Breaking-change gate | oasdiff, CI job `openapi-breaking` | GitHub Actions |
| Documentation currency | CI job `docs-current` | GitHub Actions |
| Dependency alerts and updates | Dependabot | works on private repositories |
| Direct pushes to `main` | `.githooks/pre-push` | **local only, advisory** |

Every CI check still runs on every pull request. What is gone is the layer that
made passing them *mandatory*: a pull request can now be merged with checks
red, and a commit can be pushed straight to `main` without one.

The `pre-push` hook is back to being the only thing between a mistake and
`main`. Its comments were rewritten, because under ADR 0009 they told the reader
that bypassing it would not help since a ruleset would reject the push anyway.
That is now false, and a hook that overstates its own authority is worse than no
hook.

## Consequences

The honest summary is that this repository's protection is now conventions plus
one local hook. That was true for M0 through M3 and the project shipped four
milestones under it, so it is workable — but ADR 0007 said the quiet part
already: the hook is bypassed by `--no-verify` and by any clone that has not run
`git config core.hooksPath .githooks`. It catches accidents, not intent.

**The `CodeQL` required status check could not be removed.** The ruleset API
returns `403` while the repository is private, so the stored ruleset can be
neither read nor edited. It is dormant, not deleted: if the repository is ever
made public again the ruleset resumes — including a required `CodeQL` check that
no longer has a producer, since the in-repository CodeQL job was removed in
favour of default setup (ADR 0011).

So going public again is not a one-step change. The order matters:

1. Make the repository public.
2. **Immediately** re-enable CodeQL default setup, or remove `CodeQL` from the
   ruleset's required checks. Until one of those is done, every pull request
   waits on a check that will never arrive.

That sequence is recorded here because the failure it prevents is silent in one
direction and blocking in the other, and neither is obvious from the settings
page.

Lesson, and this is the third time this project has met it in different clothes:
a Renovate configuration nobody installed (ADR 0007), a CSRF setup no real
client could satisfy while the suite passed by constructing the token itself
(ADR 0012), and now a required check with no producer. **A control is not a
control until you have watched it refuse something** — and something that
refused once can stop refusing without telling you.
