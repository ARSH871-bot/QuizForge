# 6. No concurrent automated code-modification tools

Date: 2026-08-12

## Status

Accepted

## Context

During M0 implementation, an automated code-modification tool running as an
editor extension made unrequested changes to the working tree while work was
in progress. Observed within a single session:

- `application.properties` was reverted from the new PostgreSQL configuration
  back to the original MySQL configuration, after the application had already
  been verified running against PostgreSQL.
- `java.version` and `maven.compiler.release` were changed from 21 to 25.
  Only JDK 21 is installed, so every subsequent build failed with
  `release version 25 not supported`.
- An H2 test dependency was added, contradicting the Testcontainers strategy
  in ADR 0002 and this project's rule that tests run on the same engine as
  production.
- `src/test/resources/application.properties` was created with a hardcoded
  datasource, overriding the Testcontainers harness.
- `src/main/resources/db/migration/V1__baseline.sql` was deleted after it had
  been written and verified, and before it could be committed.
- A rival `V1__baseline_schema.sql` was created, giving two migrations the
  same version and causing Flyway to fail.

The most serious consequence was silent contamination of history: commit
`633cffb` captured the foreign `java.version=25` change because it was swept
in by `git add -A` alongside legitimate work.

## Decision

Automated code-modification tools must not run against this repository
concurrently with development. This covers IDE migration assistants, agent
extensions, and any tool that writes to the working tree without an explicit
per-change request.

Where such a tool is genuinely wanted, it runs deliberately: on a clean tree,
on its own branch, with its diff reviewed before commit.

## Consequences

Positive: commits contain only intended changes. Build configuration stays
reproducible. Verification results remain meaningful — a passing test proves
something about the committed code rather than about a tree that has since
changed underneath it.

Negative: loses whatever value those tools provide. Given that this one
deleted a verified migration and broke the build twice in one session, that
trade is clearly worth making.

## Detection

Symptoms that this is happening again: `git status` showing modifications
nobody made, build failures citing a Java release that is not installed,
duplicate migration versions, or test resources appearing unbidden. Confirm
with `git diff` before assuming the cause lies in your own work.
