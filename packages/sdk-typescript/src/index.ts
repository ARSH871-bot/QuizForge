/**
 * The QuizForge TypeScript client.
 *
 * ```ts
 * import { QuizForge, QuizForgeError } from "@quizforge/sdk";
 *
 * const qf = new QuizForge({ apiKey: process.env.QUIZFORGE_API_KEY! });
 * const bank = await qf.questionBanks.create({ name: "Geography" });
 * ```
 */
export { QuizForge } from "./client.js";
export type {
  ListOptions,
  WriteOptions,
  Account,
  ApiKeySummary,
  AttemptSummary,
  ImportReport,
  IssuedApiKey,
  LeaderboardEntry,
  Member,
  Question,
  QuestionBank,
  QuestionPayload,
  QuestionType,
  Role,
  TournamentDraft,
  TournamentSummary,
  Workspace,
} from "./client.js";
export { QuizForgeError, QuizForgeConnectionError } from "./errors.js";
export type { ErrorCode, Problem } from "./errors.js";
export type { ClientOptions } from "./http.js";

/** The generated contract types, for callers that want the raw shapes. */
export type { components, paths } from "./types.js";
