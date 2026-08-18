# 11. Generate interfaces from the contract, not controllers

Date: 2026-08-18

## Status

Accepted.

## Context

§6 of the platform design commits to contract-first: `openapi.yaml` is the
source of truth and the Spring server is generated from it. That leaves a
choice the spec did not make — *how much* to generate.

Until now the relationship ran the other way. Controllers were hand-written and
the contract described them, which meant the contract was a document that
happened to be accurate on the day it was written. `docs-current` could enforce
that `openapi.yaml` changed alongside a controller, but not that it changed
*correctly*.

## Decision

`openapi-generator-maven-plugin` runs with `interfaceOnly=true`. It produces
one interface per tag and one model per schema, into
`target/generated-sources`. Controllers `implement` those interfaces and keep
their own bodies.

Generated code is **not** committed. It is a build product; committing it
invites hand edits that the next build silently discards.

Controller bodies are not generated, for the same reason: generated code in
`src/main/java` is code nobody can debug and everybody eventually edits.

## Consequences

A controller whose path, verb, parameters, status or response type disagrees
with the published contract **no longer compiles**. Drift stops being something
review has to notice.

The models carry the contract's own constraints — `@Pattern` on identifiers,
`@Min`/`@Max` on page sizes, `@NotNull` on required fields — so validation is
derived from the document rather than restated in Java and kept in step by
hand.

Three consequences were not anticipated and are worth recording, because each
changed the code rather than the configuration.

**Credentials are not parameters.** `logout()` generates with no arguments: the
session cookie is a `securityScheme`, and the generator does not turn a
credential into a method argument. That is correct — a credential is not part
of an operation's signature — but it means `@CookieValue` cannot be used, and
the cookie is read from the injected request instead.

**The principal is not a parameter either.** `@AuthenticationPrincipal` was an
argument no contract would ever describe, so it could not survive the retrofit.
Controllers now read the principal from the security context through
`CurrentPrincipal`. Again the constraint is pointing at something true: who is
calling is ambient to the request, not part of its contract.

**Contract validation exposed a gap in error mapping.** Once the generated
constraints started rejecting requests, those rejections arrived as
`HandlerMethodValidationException`, which `GlobalExceptionHandler` did not
handle — so they fell to the catch-all and were reported as **500**. A caller
sending `limit=1000` was told the server had failed, when the server had
correctly refused them. Fixed by mapping parameter validation to
`INVALID_REQUEST`. Nothing about that was visible until validation moved into
the contract.

**Generated code is excluded from SpotBugs.** The models expose mutable lists
and `EI_EXPOSE_REP` is technically right about them, but the only way to act on
it would be to stop generating them. Reporting a defect nobody can fix trains
readers to skim the report — the failure ADR 0007 recorded for the legacy
package. The hand-written code that builds the models is still analysed.

**Spring Modulith needed the generated package declared.** `com.quizforge.api`
is a direct subpackage of the base package, so Modulith treats it as an
application module and every dependency on it was a violation. A hand-written
`package-info.java` declares it, and `api::model` is a named interface. That is
a fair description of what it is: a module with no dependencies that everything
else may use, because the contract is what the whole application agrees on.

## The breaking-change gate

`oasdiff` compares each pull request's contract against the base branch and
fails on a breaking change. `/v1` is additive-only.

The gate was verified by watching it reject one, and the rejection was real
rather than staged — the very change that introduced it makes seven breaking
changes, because it corrects shapes that should never have been published:

```
7 changes: 7 error, 0 warning, 0 info
error [response-body-type-changed]     GET /v1/tournaments
      the response's body `type` changed from `array<object>` to `object`
error [response-property-type-changed] GET /v1/attempts/{attemptId}
      the `attemptId` response's property `format` changed from `uuid` to `none`
error [request-property-became-required] POST /v1/auth/request-password-reset
```

That output is the argument for having made those corrections immediately. Each
line is a change that would have been impossible a milestone later.

This is the last such change. From the commit that adds the gate onwards, a
breaking change to `/v1` fails CI and waits for a `/v2` that does not exist and
should not for a long time.
