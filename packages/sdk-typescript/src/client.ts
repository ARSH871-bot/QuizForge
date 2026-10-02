import { Http } from "./http.js";
import type { ClientOptions } from "./http.js";
import type { components } from "./types.js";

type S = components["schemas"];

export type Workspace = S["Workspace"];
export type Member = S["Member"];
export type Role = S["Role"];
export type ApiKeySummary = S["ApiKeySummary"];
export type IssuedApiKey = S["IssuedApiKey"];
export type QuestionBank = S["QuestionBank"];
export type Question = S["Question"];
export type QuestionPayload = S["QuestionPayload"];
export type QuestionType = S["QuestionType"];
export type ImportReport = S["ImportReport"];
export type TournamentSummary = S["TournamentSummary"];
export type TournamentDraft = S["TournamentDraft"];
export type AttemptSummary = S["AttemptSummary"];
export type LeaderboardEntry = S["LeaderboardEntry"];
export type QuestionStat = S["QuestionStat"];
export type Account = S["Account"];

/** Options every list method accepts. */
export interface ListOptions {
  /** Items per request while iterating. 1–100; the API defaults to 25. */
  limit?: number;
}

/** Options every mutating method accepts. */
export interface WriteOptions {
  /**
   * Reuse a key to retry a specific request.
   *
   * Omit it and the client generates one per call, which already makes its own
   * retries safe. Pass one when *your* code retries across process restarts.
   */
  idempotencyKey?: string;
}

/**
 * The QuizForge API.
 *
 * ```ts
 * const qf = new QuizForge({ apiKey: process.env.QUIZFORGE_API_KEY! });
 *
 * for await (const bank of qf.questionBanks.list()) {
 *   console.log(bank.name);
 * }
 * ```
 *
 * An API key authenticates a workspace, so nothing here takes a workspace
 * argument — the key already says which one.
 *
 * ## What a key can do today
 *
 * **Reads work. Writes do not.** Every write is audited against the account
 * that made it, and a key names a workspace rather than a person, so the API
 * answers `PERMISSION_DENIED` to all of them. The write methods are here
 * because the contract defines them and a session-authenticated caller can
 * reach them; from a key they will throw until issue #98 decides how a
 * key-authored write is attributed.
 *
 * Playing — starting and submitting attempts — is absent entirely: an attempt
 * belongs to a player, and there is no player behind a key.
 */
export class QuizForge {
  private readonly http: Http;

  constructor(options: ClientOptions) {
    this.http = new Http(options);
  }

  /** The workspace this key belongs to. */
  readonly workspaces = {
    /** The workspace this key belongs to. */
    current: (): Promise<Workspace> =>
      this.http.request({ method: "GET", path: "/v1/workspaces/current" }),

    /** Requires a session and `OWNER`. See the note on {@link QuizForge}. */
    rename: (name: string, options: WriteOptions = {}): Promise<Workspace> =>
      this.http.request({
        method: "PATCH", path: "/v1/workspaces/current", body: { name },
        ...options,
      }),
  };

  /** Who belongs to the workspace. */
  readonly members = {
    /** Every member, following cursors. */
    list: (options: ListOptions = {}): AsyncGenerator<Member> =>
      this.http.paginate<Member>({ path: "/v1/members", query: { limit: options.limit } }),

    /** Adds an existing account by email. */
    add: (email: string, role: Role, options: WriteOptions = {}): Promise<Member> =>
      this.http.request({ method: "POST", path: "/v1/members", body: { email, role }, ...options }),

    changeRole: (accountId: string, role: Role, options: WriteOptions = {}): Promise<Member> =>
      this.http.request({
        method: "PATCH", path: `/v1/members/${accountId}`, body: { role }, ...options,
      }),

    remove: (accountId: string, options: WriteOptions = {}): Promise<void> =>
      this.http.request({ method: "DELETE", path: `/v1/members/${accountId}`, ...options }),
  };

  /**
   * API keys.
   *
   * All of these require a session: a key cannot mint or revoke another key,
   * so a leaked credential cannot extend its own foothold. That one is a
   * deliberate rule rather than the general write restriction, and will not be
   * lifted by issue #98.
   */
  readonly apiKeys = {
    list: (options: ListOptions = {}): AsyncGenerator<ApiKeySummary> =>
      this.http.paginate<ApiKeySummary>({ path: "/v1/api-keys", query: { limit: options.limit } }),

    /** The only call that ever returns a secret. Store it; it is not repeatable. */
    create: (
      name: string,
      environment: "live" | "test" = "live",
      options: WriteOptions = {},
    ): Promise<IssuedApiKey> =>
      this.http.request({
        method: "POST", path: "/v1/api-keys", body: { name, environment }, ...options,
      }),

    revoke: (keyId: string, options: WriteOptions = {}): Promise<void> =>
      this.http.request({ method: "DELETE", path: `/v1/api-keys/${keyId}`, ...options }),
  };

  /** Collections of questions a tournament draws from. */
  readonly questionBanks = {
    list: (options: ListOptions = {}): AsyncGenerator<QuestionBank> =>
      this.http.paginate<QuestionBank>({
        path: "/v1/question-banks", query: { limit: options.limit },
      }),

    get: (bankId: string): Promise<QuestionBank> =>
      this.http.request({ method: "GET", path: `/v1/question-banks/${bankId}` }),

    create: (
      bank: { name: string; description?: string },
      options: WriteOptions = {},
    ): Promise<QuestionBank> =>
      this.http.request({ method: "POST", path: "/v1/question-banks", body: bank, ...options }),

    /** Archives rather than deletes, so played tournaments stay explicable. */
    archive: (bankId: string, options: WriteOptions = {}): Promise<void> =>
      this.http.request({ method: "DELETE", path: `/v1/question-banks/${bankId}`, ...options }),
  };

  /** Questions. Immutable and versioned: revising creates a new version. */
  readonly questions = {
    /** The current version of every question in a bank. */
    list: (bankId: string, options: ListOptions = {}): AsyncGenerator<Question> =>
      this.http.paginate<Question>({
        path: `/v1/question-banks/${bankId}/questions`, query: { limit: options.limit },
      }),

    get: (questionId: string): Promise<Question> =>
      this.http.request({ method: "GET", path: `/v1/questions/${questionId}` }),

    /** Every version of a question, oldest first. */
    versions: (questionId: string, options: ListOptions = {}): AsyncGenerator<Question> =>
      this.http.paginate<Question>({
        path: `/v1/questions/${questionId}/versions`, query: { limit: options.limit },
      }),

    author: (
      bankId: string,
      question: {
        type: QuestionType;
        prompt: string;
        payload: QuestionPayload;
        difficulty?: string;
      },
      options: WriteOptions = {},
    ): Promise<Question> =>
      this.http.request({
        method: "POST", path: `/v1/question-banks/${bankId}/questions`,
        body: question, ...options,
      }),

    /**
     * Creates a **new version**; the one addressed is left untouched.
     *
     * The returned question has a different `id` — that is the new version.
     * `lineageId` is what stays the same.
     */
    revise: (
      questionId: string,
      revision: { prompt: string; payload: QuestionPayload; difficulty?: string },
      options: WriteOptions = {},
    ): Promise<Question> =>
      this.http.request({
        method: "PATCH", path: `/v1/questions/${questionId}`, body: revision, ...options,
      }),

    retire: (questionId: string, options: WriteOptions = {}): Promise<void> =>
      this.http.request({ method: "DELETE", path: `/v1/questions/${questionId}`, ...options }),
  };

  /** Bulk loading. Both report partial success rather than failing wholesale. */
  readonly imports = {
    /**
     * Imports CSV. Check the report: a resolved promise means the file was
     * processed, not that every row succeeded.
     */
    csv: (bankId: string, csv: string, options: WriteOptions = {}): Promise<ImportReport> =>
      this.http.request({
        method: "POST", path: `/v1/question-banks/${bankId}/imports/csv`,
        body: csv, contentType: "text/csv", ...options,
      }),

    openTdb: (
      bankId: string,
      request: { amount: number; category?: number; difficulty?: "easy" | "medium" | "hard" },
      options: WriteOptions = {},
    ): Promise<ImportReport> =>
      this.http.request({
        method: "POST", path: `/v1/question-banks/${bankId}/imports/opentdb`,
        body: request, ...options,
      }),
  };

  /** Tournaments. */
  readonly tournaments = {
    list: (options: ListOptions = {}): AsyncGenerator<TournamentSummary> =>
      this.http.paginate<TournamentSummary>({
        path: "/v1/tournaments", query: { limit: options.limit },
      }),

    get: (tournamentId: string): Promise<TournamentSummary> =>
      this.http.request({ method: "GET", path: `/v1/tournaments/${tournamentId}` }),

    create: (draft: TournamentDraft, options: WriteOptions = {}): Promise<TournamentSummary> =>
      this.http.request({ method: "POST", path: "/v1/tournaments", body: draft, ...options }),

    /** Refused once the tournament is open; its players started under its rules. */
    update: (
      tournamentId: string,
      draft: TournamentDraft,
      options: WriteOptions = {},
    ): Promise<TournamentSummary> =>
      this.http.request({
        method: "PATCH", path: `/v1/tournaments/${tournamentId}`, body: draft, ...options,
      }),

    /** Only while scheduled, and only with no attempts against it. */
    delete: (tournamentId: string, options: WriteOptions = {}): Promise<void> =>
      this.http.request({ method: "DELETE", path: `/v1/tournaments/${tournamentId}`, ...options }),

    /** Every attempt, including ones in progress that rank nowhere. */
    attempts: (tournamentId: string, options: ListOptions = {}): AsyncGenerator<AttemptSummary> =>
      this.http.paginate<AttemptSummary>({
        path: `/v1/tournaments/${tournamentId}/attempts-summary`,
        query: { limit: options.limit },
      }),

    /**
     * The ranked leaderboard.
     *
     * A bounded top-N rather than a paged collection — ranking is resolved when
     * the standings are read, so there is no cursor to follow. Returns an array
     * rather than an iterator, and says so by its type.
     */
    standings: async (tournamentId: string, limit = 20): Promise<LeaderboardEntry[]> => {
      const page = await this.http.request<{ data: LeaderboardEntry[] }>({
        method: "GET", path: `/v1/tournaments/${tournamentId}/standings`, query: { limit },
      });
      return page.data;
    },

    /**
     * How each question went across finished attempts, hardest first.
     *
     * One row per question the tournament drew, so the whole report fits in
     * one response; returns an array, as {@link standings} does. Carries each
     * prompt but never the answer, and a `PLAYER` is refused.
     */
    questionStats: async (tournamentId: string): Promise<QuestionStat[]> => {
      const page = await this.http.request<{ data: QuestionStat[] }>({
        method: "GET", path: `/v1/tournaments/${tournamentId}/question-stats`,
      });
      return page.data;
    },
  };
}
