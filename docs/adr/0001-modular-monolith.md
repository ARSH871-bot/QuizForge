# 1. Modular monolith over microservices

Date: 2026-08-11

## Status

Accepted

## Context

QuizForge is built by one developer over roughly six months with a hard
constraint of near-zero infrastructure spend before launch. It needs clear
internal boundaries so that the domain stays comprehensible as it grows.

## Decision

A single deployable Spring Boot application, internally divided into modules
whose boundaries are enforced at build time by Spring Modulith and ArchUnit.
Modules communicate through published application events and explicit public
APIs, never through each other's repositories.

## Consequences

Positive: one deployment, one database, one transaction boundary when needed,
and no distributed-systems failure modes. Boundaries are enforced by failing
tests rather than by convention, so they do not erode. Extraction into
services later remains possible because the seams are real.

Negative: the whole application scales as a unit, and a defect in one module
can affect the process as a whole. Both are acceptable at the expected scale.
