## What

<!-- One or two sentences. What changes for a user or a caller of the API? -->

## Why

<!-- Link the issue: "Closes #123". If there is no issue, explain the trigger. -->

## How

<!-- Anything a reviewer could not infer from the diff. Trade-offs, rejected
     alternatives, and anything you are unsure about. -->

## Verification

<!-- The commands you actually ran and what they output. Not "tests pass". -->

```
$ cd apps/api && ./mvnw verify
```

## Checklist

- [ ] `./mvnw verify` passes locally
- [ ] Tests cover the change (new behaviour has a failing-first test)
- [ ] Schema changes are a Flyway migration, not an entity-only change
- [ ] No secret, credential, or personal data added to a tracked file
- [ ] Structural decisions recorded as an ADR in `docs/adr/`
- [ ] Public API changes reflected in `openapi.yaml`
