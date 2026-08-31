# Security Policy

## Reporting a vulnerability

**The repository is currently private, and private vulnerability reporting is
unavailable on the Free plan** (ADR 0013). The advisory form that this file
used to link to returns an error rather than a form, so it is not linked here —
a reporting channel that does not work is worse than an obvious absence.

While the repository is private, everyone who can read this file is a
collaborator, and the channel is whatever you already use to reach the owner
directly. Do not open an issue: issues on this repository are for planned work
and are read as such.

At launch the repository becomes reachable by people who are not collaborators,
and this section is replaced by a working channel before that happens — a
private advisory if the plan allows it, otherwise an address on the project's
own domain. No email is published here in the meantime: an address that bounces
is worse than no address at all, and this project does not yet have a domain.

## What to include

- What the issue is, and the impact if exploited
- Steps to reproduce — a request, a test, or a sequence of commands
- The commit SHA you observed it on

## Response

This is currently a solo project, so response times are best-effort rather
than contractual. Expect acknowledgement within a week.

## Scope

In scope: authentication, authorization, tenant isolation, data exposure,
injection, and anything permitting access to another workspace's data.

Out of scope: findings requiring physical access, social engineering, and
denial of service through volumetric traffic.

## Supported versions

The project is pre-launch. Only `main` receives fixes.
