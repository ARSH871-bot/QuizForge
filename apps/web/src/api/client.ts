import type { components } from "./schema";

type S = components["schemas"];
export type Account = S["Account"];
export type Workspace = S["Workspace"];
export type Tournament = S["TournamentSummary"];
export type PublicTournament = S["PublicTournament"];
export type Enrolment = S["Enrolment"];
export type AttemptStarted = S["AttemptStarted"];
export type PlayableQuestion = S["PlayableQuestion"];
export type AnswerFeedback = S["AnswerFeedback"];
export type AttemptResult = S["AttemptResult"];
export type Standing = S["LeaderboardEntry"];
export type QuestionType = S["QuestionType"];
export type QuestionPayload = S["QuestionPayload"];
export type Problem = S["Problem"];

/** An API failure, carrying the problem document's stable code and its human detail. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
  ) {
    super(message);
  }
}

const WORKSPACE_KEY = "qf.workspace";

export function currentWorkspace(): string | null {
  try {
    return localStorage.getItem(WORKSPACE_KEY);
  } catch {
    return null;
  }
}

export function selectWorkspace(id: string | null): void {
  try {
    if (id) localStorage.setItem(WORKSPACE_KEY, id);
    else localStorage.removeItem(WORKSPACE_KEY);
  } catch {
    // Private browsing: the selection simply lasts for this page.
  }
}

/**
 * The CSRF token the API sets as a readable cookie. Sent back unchanged on
 * every write; the API compares it with the cookie, which another site cannot
 * read.
 */
function csrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match?.[1] ? decodeURIComponent(match[1]) : undefined;
}

interface Options {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  /** Send the selected workspace. Off for account-scoped calls. */
  workspace?: string | null | false;
}

async function request<T>(path: string, options: Options = {}): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json, application/problem+json" };

  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") {
    // The token cookie is issued on the first response; make sure one exists.
    if (!csrfToken()) await fetch("/v1/auth/health", { credentials: "same-origin" });
    const token = csrfToken();
    if (token) headers["X-XSRF-TOKEN"] = token;
  }
  const workspace = options.workspace === undefined ? currentWorkspace() : options.workspace;
  if (workspace) headers["X-QuizForge-Workspace"] = workspace;

  const response = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });

  const text = await response.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    const problem = data as Problem | undefined;
    throw new ApiError(
      response.status,
      problem?.code,
      problem?.detail ?? `Something went wrong (${response.status}). Try again.`,
    );
  }
  return data as T;
}

export const api = {
  me: () => request<Account>("/v1/auth/me", { workspace: false }),
  register: (email: string, password: string, displayName: string) =>
    request<Account>("/v1/auth/register", {
      method: "POST",
      body: { email, password, displayName },
      workspace: false,
    }),
  login: (email: string, password: string) =>
    request<Account>("/v1/auth/login", { method: "POST", body: { email, password }, workspace: false }),
  logout: () => request<void>("/v1/auth/logout", { method: "POST", workspace: false }),

  workspaces: () =>
    request<{ data: Workspace[] }>("/v1/workspaces?limit=100", { workspace: false }).then((p) => p.data),
  createWorkspace: (name: string) =>
    request<Workspace>("/v1/workspaces", { method: "POST", body: { name }, workspace: false }),

  tournaments: () =>
    request<{ data: Tournament[] }>("/v1/tournaments?limit=100").then((p) => p.data),
  tournament: (id: string) => request<Tournament>(`/v1/tournaments/${id}`),
  createBank: (name: string) =>
    request<{ id: string }>("/v1/question-banks", { method: "POST", body: { name } }),
  authorQuestion: (bankId: string, question: { type: QuestionType; prompt: string; payload: QuestionPayload }) =>
    request<{ id: string }>(`/v1/question-banks/${bankId}/questions`, { method: "POST", body: question }),
  createTournament: (draft: {
    name: string;
    bankId: string;
    opensAt: string;
    closesAt: string;
    questions: number;
    maxAttempts: number;
    timeLimitSeconds?: number;
    scoringPolicy: "BEST" | "FIRST" | "LAST" | "AVERAGE";
  }) => request<Tournament>("/v1/tournaments", { method: "POST", body: draft }),
  playCount: (tournamentId: string) =>
    request<{ data: unknown[] }>(`/v1/tournaments/${tournamentId}/attempts-summary?limit=100`).then(
      (p) => p.data.length,
    ),

  publicTournament: (id: string) => request<PublicTournament>(`/v1/join/${id}`, { workspace: false }),
  join: (id: string) => request<Enrolment>(`/v1/join/${id}`, { method: "POST", workspace: false }),

  startAttempt: (tournamentId: string) =>
    request<AttemptStarted>(`/v1/tournaments/${tournamentId}/attempts`, { method: "POST" }),
  question: (attemptId: string, position: number) =>
    request<PlayableQuestion>(`/v1/attempts/${attemptId}/questions/${position}`),
  answer: (attemptId: string, position: number, answer: string) =>
    request<AnswerFeedback>(`/v1/attempts/${attemptId}/questions/${position}/answer`, {
      method: "POST",
      body: { answer },
    }),
  submit: (attemptId: string) => request<AttemptResult>(`/v1/attempts/${attemptId}/submit`, { method: "POST" }),
  standings: (tournamentId: string) =>
    request<{ data: Standing[] }>(`/v1/tournaments/${tournamentId}/standings?limit=50`).then((p) => p.data),
};
