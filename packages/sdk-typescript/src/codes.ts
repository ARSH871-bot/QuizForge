// Generated from openapi.yaml by scripts/codes.mjs. Do not edit.

/** The error codes the API documents today, with their HTTP status and meaning. */
export const KNOWN_ERROR_CODES = {
  INVALID_REQUEST: { status: 400, meaning: "Malformed input, a failed validation rule, or an operation the current state does not allow." },
  INVALID_CREDENTIALS: { status: 401, meaning: "The email or password was wrong. Which one is never disclosed." },
  AUTHENTICATION_REQUIRED: { status: 401, meaning: "No credential was presented, or it has expired or been revoked." },
  PERMISSION_DENIED: { status: 403, meaning: "Authenticated, but the role is insufficient for this action." },
  INVALID_CURSOR: { status: 400, meaning: "The pagination cursor was not issued by this API." },
  NOT_FOUND: { status: 404, meaning: "No such object, or it belongs to another tenant. Deliberately ambiguous." },
  ALREADY_EXISTS: { status: 409, meaning: "A uniqueness constraint would be violated, or a request with the same Idempotency-Key is still running." },
  ATTEMPTS_EXHAUSTED: { status: 409, meaning: "The player has used every attempt the tournament allows. Their best result stands." },
  IDEMPOTENCY_KEY_REUSED: { status: 422, meaning: "The Idempotency-Key was already used for a different request." },
  RATE_LIMITED: { status: 429, meaning: "Too many requests for this credential, or too many new guests from one network address. Retry-After says when to try again." },
  INTERNAL: { status: 500, meaning: "An unhandled failure. The cause is logged and never returned." },
} as const;

/** A code the API documents today. */
export type KnownErrorCode = keyof typeof KNOWN_ERROR_CODES;
