import type { components } from "./types.js";

/** The RFC 9457 Problem Details document every error response carries. */
export type Problem = components["schemas"]["Problem"];

/**
 * The stable machine-readable error code.
 *
 * <p>Branch on this, never on `detail`. A code that exists will never change
 * its meaning or its HTTP status; `detail` is written for humans and is
 * reworded freely.
 */
export type ErrorCode = components["schemas"]["ErrorCode"];

/**
 * An error the API returned.
 *
 * The `code` is the part worth writing code against:
 *
 * ```ts
 * try {
 *   await qf.questionBanks.create({ name: "Geography" });
 * } catch (error) {
 *   if (error instanceof QuizForgeError && error.code === "ALREADY_EXISTS") {
 *     // a bank by that name is already there
 *   }
 * }
 * ```
 */
export class QuizForgeError extends Error {
  /** The stable code. Absent only if a proxy returned a non-API error page. */
  readonly code: ErrorCode | undefined;

  /** The HTTP status. */
  readonly status: number;

  /** The full problem document, when the response carried one. */
  readonly problem: Problem | undefined;

  /** Seconds to wait, on a 429 that carried `Retry-After`. */
  readonly retryAfterSeconds: number | undefined;

  /** The request that failed, for logging. Never carries credentials. */
  readonly method: string;
  readonly path: string;

  constructor(init: {
    message: string;
    status: number;
    method: string;
    path: string;
    code?: ErrorCode;
    problem?: Problem;
    retryAfterSeconds?: number;
  }) {
    super(init.message);
    this.name = "QuizForgeError";
    this.status = init.status;
    this.method = init.method;
    this.path = init.path;
    this.code = init.code;
    this.problem = init.problem;
    this.retryAfterSeconds = init.retryAfterSeconds;
  }

  /**
   * Whether retrying could plausibly succeed.
   *
   * Rate limits and server errors, yes. A validation failure or a permission
   * problem will fail the same way however many times it is sent.
   */
  get isRetryable(): boolean {
    return this.status === 429 || this.status >= 500;
  }
}

/** Thrown when the API could not be reached at all. */
export class QuizForgeConnectionError extends Error {
  // `Error.cause` is standard as of ES2022, so this narrows the base member
  // rather than adding one: the original failure is always present here.
  override readonly cause: unknown;

  constructor(message: string, cause: unknown) {
    super(message);
    this.name = "QuizForgeConnectionError";
    this.cause = cause;
  }
}
