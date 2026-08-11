# 4. PostgreSQL only; no Redis

Date: 2026-08-11

## Status

Accepted

## Context

Caching, rate limiting, job queueing, and the transactional outbox are all
commonly delegated to Redis. Each additional service is another process to
run, monitor, back up, and pay for — against a near-zero budget.

## Decision

PostgreSQL serves all four needs. Rate limiting uses a token-bucket table, the
outbox is a table drained by a background worker, and caching is handled
in-process.

## Consequences

Positive: one stateful service to operate and back up. Outbox writes share a
transaction with the domain writes that produce them, which is precisely the
property that makes the pattern correct.

Negative: a busy outbox creates write load on the primary database, and
in-process caching does not survive a restart. Both are revisitable; if either
becomes a real measured problem, adding Redis is a contained change.
