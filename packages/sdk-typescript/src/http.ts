import { QuizForgeConnectionError, QuizForgeError } from "./errors.js";
import type { ErrorCode, Problem } from "./errors.js";

/** How the client was configured. */
export interface ClientOptions {
  /** An API key: `qf_live_…` or `qf_test_…`. */
  apiKey: string;

  /** Where the API lives. Defaults to the local development instance. */
  baseUrl?: string;

  /**
   * How many times to retry a request that failed retryably.
   *
   * Defaults to 2, so a request is attempted at most three times. Zero
   * disables retrying entirely.
   */
  maxRetries?: number;

  /** Swappable for tests. Defaults to the global `fetch`. */
  fetch?: typeof globalThis.fetch;

  /** Swappable for tests, so backoff does not make a suite slow. */
  sleep?: (ms: number) => Promise<void>;
}

interface RequestOptions {
  method: string;
  path: string;
  query?: Record<string, string | number | undefined>;
  body?: unknown;

  /**
   * Overrides the request content type.
   *
   * When set, `body` is sent as-is rather than JSON-encoded. The CSV import is
   * the only caller: it takes `text/csv`, and JSON-encoding a CSV document
   * would send a quoted string the server cannot parse.
   */
  contentType?: string;

  /**
   * Overrides the generated idempotency key.
   *
   * Pass the *same* value to retry the *same* request. Omit it and the client
   * generates one per call, which is what makes its own retries safe.
   */
  idempotencyKey?: string;
}

const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * The transport.
 *
 * Everything that is the same for every call lives here: authentication,
 * idempotency, retries and turning a Problem Details body into a typed error.
 */
export class Http {
  private readonly apiKey: string;
  private readonly baseUrl: string;
  private readonly maxRetries: number;
  private readonly fetchImpl: typeof globalThis.fetch;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(options: ClientOptions) {
    if (!options.apiKey) {
      throw new Error("An API key is required. Mint one with POST /v1/api-keys.");
    }

    this.apiKey = options.apiKey;
    this.baseUrl = (options.baseUrl ?? "http://localhost:8080").replace(/\/+$/, "");
    this.maxRetries = options.maxRetries ?? 2;
    this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.sleep = options.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  }

  async request<T>(options: RequestOptions): Promise<T> {
    const url = this.url(options.path, options.query);
    const mutating = MUTATING.has(options.method);

    // One key for the whole call, including this client's own retries. That is
    // the point: without it a retry after a timeout could do the work twice,
    // which is exactly the failure the server's Idempotency-Key exists to stop.
    const idempotencyKey = mutating
      ? options.idempotencyKey ?? crypto.randomUUID()
      : undefined;

    let attempt = 0;

    for (;;) {
      let response: Response;
      try {
        response = await this.fetchImpl(url, {
          method: options.method,
          headers: this.headers(options, idempotencyKey),
          body: this.encode(options),
        });
      } catch (cause) {
        // The request may or may not have arrived. Retrying is safe anyway,
        // because a mutating call carries the same idempotency key each time.
        if (attempt < this.maxRetries) {
          await this.backoff(attempt++, undefined);
          continue;
        }
        throw new QuizForgeConnectionError(
          `Could not reach ${this.baseUrl}: ${String(cause)}`, cause);
      }

      if (response.ok || response.status === 204) {
        return (await this.parse(response)) as T;
      }

      const error = await this.toError(response, options);

      if (error.isRetryable && attempt < this.maxRetries) {
        await this.backoff(attempt++, error.retryAfterSeconds);
        continue;
      }

      throw error;
    }
  }

  /**
   * Pages through a collection, following cursors.
   *
   * Yields items rather than pages, because "give me everything" is what
   * callers almost always mean and cursor bookkeeping is what they almost
   * always get wrong.
   */
  async *paginate<T>(options: Omit<RequestOptions, "method" | "body">): AsyncGenerator<T> {
    let cursor: string | undefined;

    for (;;) {
      const page = await this.request<{ data: T[]; nextCursor?: string | null }>({
        method: "GET",
        path: options.path,
        query: { ...options.query, ...(cursor ? { cursor } : {}) },
      });

      for (const item of page.data ?? []) {
        yield item;
      }

      // Absent and null both mean stop. Asking for another page would return
      // an empty one forever.
      if (!page.nextCursor) {
        return;
      }
      cursor = page.nextCursor;
    }
  }

  private headers(options: RequestOptions, idempotencyKey: string | undefined): HeadersInit {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.apiKey}`,
      Accept: "application/json, application/problem+json",
    };

    if (options.body !== undefined) {
      headers["Content-Type"] = options.contentType ?? "application/json";
    }
    if (idempotencyKey) {
      headers["Idempotency-Key"] = idempotencyKey;
    }
    return headers;
  }

  private encode(options: RequestOptions): string | undefined {
    if (options.body === undefined) {
      return undefined;
    }
    // A raw content type means the caller already has the bytes it wants sent.
    return options.contentType ? String(options.body) : JSON.stringify(options.body);
  }

  private url(path: string, query: RequestOptions["query"]): string {
    const url = new URL(this.baseUrl + path);
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value !== undefined) {
        url.searchParams.set(key, String(value));
      }
    }
    return url.toString();
  }

  private async parse(response: Response): Promise<unknown> {
    if (response.status === 204) {
      return undefined;
    }
    const text = await response.text();
    return text ? JSON.parse(text) : undefined;
  }

  private async toError(response: Response, options: RequestOptions): Promise<QuizForgeError> {
    let problem: Problem | undefined;
    try {
      const body = await response.text();
      problem = body ? (JSON.parse(body) as Problem) : undefined;
    } catch {
      // A proxy or load balancer answering with HTML. The status still tells
      // the caller something useful, so do not lose it to a parse failure.
      problem = undefined;
    }

    const retryAfter = response.headers.get("Retry-After");

    return new QuizForgeError({
      message: problem?.detail ?? `${options.method} ${options.path} failed with ${response.status}`,
      status: response.status,
      method: options.method,
      path: options.path,
      ...(problem?.code ? { code: problem.code as ErrorCode } : {}),
      ...(problem ? { problem } : {}),
      ...(retryAfter ? { retryAfterSeconds: Number(retryAfter) } : {}),
    });
  }

  /**
   * Waits before retrying.
   *
   * `Retry-After` wins when the server sent one — it knows when the bucket
   * refills and this client is only guessing. Otherwise exponential with
   * jitter, because synchronised clients retrying in lockstep is how a brief
   * problem becomes a sustained one.
   */
  private async backoff(attempt: number, retryAfterSeconds: number | undefined): Promise<void> {
    if (retryAfterSeconds !== undefined && Number.isFinite(retryAfterSeconds)) {
      await this.sleep(Math.max(0, retryAfterSeconds) * 1000);
      return;
    }
    const base = 200 * 2 ** attempt;
    await this.sleep(base + Math.random() * base);
  }
}
