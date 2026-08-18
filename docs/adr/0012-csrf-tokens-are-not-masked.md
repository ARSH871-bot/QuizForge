# 12. Do not mask the CSRF token

Date: 2026-08-18

## Status

Accepted.

## Context

CSRF protection is enabled for cookie-authenticated requests, using
`CookieCsrfTokenRepository.withHttpOnlyFalse()`. That repository exists for one
pattern: the client reads the `XSRF-TOKEN` cookie with JavaScript and echoes its
value back in the `X-XSRF-TOKEN` header. It is the pattern the contract
documents, and the one a browser client will use.

**It did not work.** Spring Security 6 defaults to
`XorCsrfTokenRequestAttributeHandler`, which masks the token: the cookie carries
the raw value while the header is expected to carry a masked one. A client that
echoes the cookie back is rejected.

Because the CSRF filter runs before authentication, the rejection surfaced as
`401 AUTHENTICATION_REQUIRED` rather than `403`, so the symptom pointed at the
credential rather than at the token.

The practical consequence was not marginal: **no cookie-authenticated write was
possible for any real client.** A developer could not create a workspace or mint
an API key, which is the entire purpose of the endpoints added in M4 Task 3.

The whole test suite passed throughout. Every write test uses
`SecurityMockMvcRequestPostProcessors.csrf()`, which constructs a valid masked
token internally — it exercises a path no browser and no `curl` invocation can
take. The suite was green about a feature that did not work.

A second defect sat behind it. Spring Security loads the token lazily, and the
cookie is only written when something reads the token — which happens on a
CSRF-checked request. Reads are not checked, so a client whose first request is
a `GET`, which is every browser client on first load, received no cookie at all
and had nothing to echo.

## Decision

Two changes, both narrow:

1. `CsrfTokenRequestAttributeHandler` with `csrfRequestAttributeName` set to
   `null`, which disables the XOR masking. The token is accepted exactly as the
   cookie carries it.
2. `CsrfCookieFilter`, one line of work, resolving the deferred token so the
   cookie is issued on every response including reads.

## Consequences

The documented pattern works, verified against a running server rather than
through MockMvc — a workspace was created, an API key minted, used, and revoked
over HTTP with nothing but the cookie and the header.

**The masking is not protecting anything here, which is why dropping it is
safe.** XOR masking defends against BREACH, an attack that recovers a secret
from a *compressed response body*. This application never renders the token into
a body: it exists in a cookie the client reads and in a request header it sends.
There is no body for BREACH to read. The protection was costing the feature and
buying nothing.

CSRF protection itself is unchanged and still enforced. A cookie-authenticated
write without the header is still refused, and there is a test for it.

**The regression guard is a unit test, not an integration test, and that is
deliberate.** No MockMvc test can catch this class of defect, because the test
support constructs the token it is supposed to be verifying. `CsrfTokenHandlingTest`
asserts the configured handler resolves the raw cookie value, and asserts that
the default handler would not — so if the override is ever removed, the reason
it exists is in the failure message.

Lesson worth carrying: **a green suite is evidence about the code paths the
tests take, not about the ones a client takes.** The gap here was not a missing
assertion; it was that the helper doing the setup was the thing that needed
testing. Where a framework's test support fabricates the input under test,
verify against a real client at least once.
