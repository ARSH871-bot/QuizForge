# 9. Make the repository public and adopt the native security controls

Date: 2026-08-17

## Status

Accepted. Supersedes [ADR 0007](0007-private-repository-security-substitutes.md).

## Context

ADR 0007 accepted a private repository on the Free plan and substituted every
control that decision cost: a `pre-push` hook for branch protection, gitleaks
for secret scanning, SpotBugs with FindSecBugs for CodeQL.

The substitutes worked, but ADR 0007 stated the weakness of the central one
plainly: the `pre-push` hook is **advisory**. It is bypassed by `--no-verify`
and by any clone that never ran `git config core.hooksPath .githooks`. That is
not a branch protection rule; it is a reminder.

Two things changed.

The repository history was purged of a live mail credential, a 1.1 MB unrelated
binary, and every authoring-tool trailer. Until that purge, publishing was not
an option — the credential was in the initial commit. The credential has since
been revoked at the provider, so it is dead text rather than a live secret.

And the owner decided the repository should be public. That is the deciding
input: every control ADR 0007 substituted for is free on public repositories.

## Decision

The repository is public. Each substitute is replaced by, or demoted beneath,
the native control it stood in for:

| Control | Before (ADR 0007) | Now |
|---|---|---|
| Branch protection | `pre-push` hook, advisory | **Ruleset on `main`**, server-side, non-bypassable |
| Secret scanning | gitleaks in CI | **GitHub secret scanning** + **push protection**, plus gitleaks retained |
| Code scanning | SpotBugs + FindSecBugs | **CodeQL default setup**, extended query suite, plus SpotBugs retained |
| Vulnerability reports | email in `SECURITY.md` | **Private vulnerability reporting** |
| Dependency updates | Dependabot | unchanged |

Two substitutes are **kept rather than removed**, deliberately:

- **gitleaks** runs on the pull request, before merge. GitHub's secret scanning
  runs on the push. Push protection closes most of that gap, but gitleaks is
  portable — it survives a migration off GitHub, which the native control does
  not. It costs one short CI job.
- **SpotBugs with FindSecBugs** finds a different class of defect than CodeQL
  and fails the build locally, so a developer sees it before pushing. It was
  also verified against a deliberately vulnerable canary; CodeQL has not been
  so verified here.

One substitute is **removed**: the `codeql` job in `ci.yml`. CodeQL default
setup and an in-repository CodeQL workflow conflict — GitHub refuses results
uploaded from an advanced configuration while default setup is enabled. Keeping
both would mean a permanently failing job. Default setup was chosen over the
advanced workflow because it needs no build maintenance, updates its own query
packs, and is the control the owner actually asked for.

The `pre-push` hook is **retained but demoted**. It is no longer the protection;
the ruleset is. It now exists to fail in one second locally rather than after a
round trip to GitHub. Its comment has been corrected — it claimed server-side
protection was impossible, which is no longer true and would mislead a reader.

## Consequences

The main gap ADR 0007 named honestly is closed. A direct push to `main` is now
rejected by GitHub itself, not by a hook the pusher can skip. This was verified
by attempting one and confirming the rejection, rather than assumed from the
ruleset's existence.

Required status checks on `main` are `build`, `secret-scan`, `docs-current`,
`conventional-title`, and `CodeQL`.

`docs-current` is required even though it runs only on pull requests. That
looked like a reason to exclude it — a check that never reports would block the
queue forever — until the ruleset itself removed the concern: direct pushes are
rejected, so **every** change to `main` arrives as a pull request, where the
check always runs. The conditional is safe precisely because the protection is
in place. This is the check that enforces the changelog discipline, so leaving
it advisory would have been the wrong half to drop.

Review approval is set to **zero required**. GitHub does not permit approving
one's own pull request, and this is a solo project; requiring one approval
would make every merge impossible. The pull request is still mandatory, so the
CI gate and the review surface both remain. This is the one place where the
protection is weaker than a team repository's, and it is a property of the team
size, not of the configuration.

Negative, and worth stating plainly: **the history is public, and GitHub still
serves pre-rewrite commits by SHA.** Objects reachable from `refs/pull/*/head`
survive a force-push and are not removed by garbage collection on request from
the API. Anyone who knows an old SHA can still read the pre-purge blob. The
credential in it is revoked, so the exposure is of a dead string — but the
correct completion of the purge requires asking GitHub Support to garbage
collect the repository, which is tracked as a follow-up.

Lesson recorded, because it cost a verification round: **a history rewrite that
does not push tags is not a history rewrite.** The purge rewrote `main`
correctly, but the three release tags still pointed at pre-rewrite commits, so
a fresh clone re-downloaded the credential. Rewriting history means rewriting
every ref that reaches it, and the check that catches this is cloning the
remote fresh and searching it — not inspecting the local repository, which
looked clean the whole time.
