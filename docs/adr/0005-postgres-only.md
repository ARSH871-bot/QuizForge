# 5. Drop MySQL support

Date: 2026-08-11

## Status

Accepted

## Context

The prototype shipped both the MySQL and PostgreSQL drivers, selecting between
them with environment variables and switching the Hibernate dialect
accordingly. Nothing ran on MySQL in any deployed environment.

## Decision

PostgreSQL only. The MySQL driver, the dialect property, and the driver class
property are removed.

## Consequences

Positive: migrations can use PostgreSQL-specific features — Row-Level Security
for tenant isolation, `jsonb`, partial indexes — none of which are portable.
Tests run against the same engine as production.

Negative: no MySQL deployment option. Nobody was asking for one.
