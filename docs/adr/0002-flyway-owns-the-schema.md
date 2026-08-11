# 2. Flyway owns the schema

Date: 2026-08-11

## Status

Accepted

## Context

The prototype used `spring.jpa.hibernate.ddl-auto=update`, letting Hibernate
mutate the schema at startup. This gives no review point, no rollback, no
record of what changed, and behaves differently across environments.

## Decision

Flyway owns the schema. Hibernate is set to `validate` and may never create or
alter anything. Every change is a numbered, reviewed migration in
`apps/api/src/main/resources/db/migration`.

## Consequences

Positive: schema changes are reviewable in pull requests, reproducible across
environments, and testable — `SchemaMigrationTest` asserts the migrations
apply and Hibernate validates against the result.

Negative: entity changes now require a hand-written migration. This is
friction by design; it is the point.
