import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import {
  api,
  ApiError,
  selectWorkspace,
  type AttemptResult,
  type Enrolment,
  type PlayableQuestion,
  type PublicTournament,
  type Standing,
} from "../api/client";
import { useSession } from "../session";
import { AuthForm, Board, Button, ErrorNote, Field, Shell, useWindow } from "../ui";

/** An attempt in progress, kept so a reload or a dropped connection resumes it. */
interface Saved {
  attemptId: string;
  workspaceId: string;
  total: number;
  position: number;
  expiresAt: string;
}

const key = (tournamentId: string) => `qf.attempt.${tournamentId}`;
const load = (tournamentId: string): Saved | null => {
  try {
    const raw = localStorage.getItem(key(tournamentId));
    const saved = raw ? (JSON.parse(raw) as Saved) : null;
    return saved && Date.parse(saved.expiresAt) > Date.now() ? saved : null;
  } catch {
    return null;
  }
};
const save = (tournamentId: string, saved: Saved | null) => {
  try {
    if (saved) localStorage.setItem(key(tournamentId), JSON.stringify(saved));
    else localStorage.removeItem(key(tournamentId));
  } catch {
    // Private browsing: the attempt still works, it just won't survive a reload.
  }
};

type Stage =
  | { name: "card" }
  | { name: "playing"; attempt: Saved }
  | { name: "done"; result: AttemptResult | null; workspaceId: string; message?: string; exhausted?: boolean };

export function Play({ id }: { id: string }) {
  const { account, refresh } = useSession();
  const [card, setCard] = useState<PublicTournament | null>(null);
  const [stage, setStage] = useState<Stage>({ name: "card" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    api.publicTournament(id).then(setCard, setError);
    const saved = load(id);
    if (saved) {
      selectWorkspace(saved.workspaceId);
      setStage({ name: "playing", attempt: saved });
    }
  }, [id]);

  // Joining differs for an account holder and a guest; everything after is the same.
  const start = async (enrol: () => Promise<Enrolment>) => {
    setBusy(true);
    setError(null);
    try {
      const { workspace } = await enrol();
      selectWorkspace(workspace.id);
      const started = await api.startAttempt(id);
      const attempt: Saved = {
        attemptId: started.id,
        workspaceId: workspace.id,
        total: started.questions,
        position: 1,
        // No time limit means the attempt lasts as long as the tournament does.
        expiresAt: started.expiresAt ?? card!.closesAt,
      };
      save(id, attempt);
      setStage({ name: "playing", attempt });
    } catch (err) {
      // Out of attempts is not a failure: the player's best result stands, so
      // show it. Anything else - not open yet, say - is shown where they are.
      if (err instanceof ApiError && err.code === "ATTEMPTS_EXHAUSTED") {
        const enrolment = await api.join(id).catch(() => null);
        if (enrolment) {
          setStage({ name: "done", result: null, workspaceId: enrolment.workspace.id, exhausted: true });
          return;
        }
      }
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const finish = useCallback(
    (result: AttemptResult | null, workspaceId: string, message?: string) => {
      save(id, null);
      setStage({ name: "done", result, workspaceId, message });
    },
    [id],
  );

  return (
    <Shell>
      <ErrorNote error={!card ? error : null} />
      {card && stage.name === "card" && (
        <Card card={card}>
          {account === undefined ? null : account === null ? (
            card.allowGuests ? (
              <GuestEntry
                card={card}
                busy={busy}
                error={error}
                onPlay={(nickname) =>
                  void start(async () => {
                    const enrolment = await api.joinAsGuest(id, nickname);
                    await refresh();
                    return enrolment;
                  })
                }
              />
            ) : (
              <>
                <p className="muted">This tournament needs an account, so the organiser can be sure who played.</p>
                <AuthForm intent="play" />
              </>
            )
          ) : (
            <>
              <ErrorNote error={error} />
              <PlayButton card={card} busy={busy} onPlay={() => void start(() => api.join(id))} />
            </>
          )}
        </Card>
      )}
      {card && stage.name === "playing" && (
        <Round tournamentId={id} card={card} attempt={stage.attempt} onFinish={finish} />
      )}
      {card && stage.name === "done" && account && (
        <Result
          tournamentId={id}
          card={card}
          result={stage.result}
          you={account.id}
          workspaceId={stage.workspaceId}
          message={stage.message}
          exhausted={stage.exhausted}
        />
      )}
    </Shell>
  );
}

function Card({ card, children }: { card: PublicTournament; children: ReactNode }) {
  const timing = useWindow(card.opensAt, card.closesAt);
  const minutes = card.timeLimitSeconds ? Math.round(card.timeLimitSeconds / 60) : null;
  return (
    <section className="card-hero">
      <p className="organiser">{card.organiser}</p>
      <h1 className="display">{card.name}</h1>
      <p className={timing.open ? "window window-open" : "window"}>{timing.text}</p>
      <p className="lede">
        {card.questions} questions
        {minutes ? `, and ${minutes} minutes to answer them all` : ", with no time limit"}.{" "}
        {card.maxAttempts === 1 ? "You get one attempt." : `You get ${card.maxAttempts} attempts, and your best counts.`}
      </p>
      {children}
    </section>
  );
}

/**
 * Playing without an account: a name and a button. Signing in stays one tap
 * away for anyone who would rather keep their results.
 */
function GuestEntry({
  card,
  busy,
  error,
  onPlay,
}: {
  card: PublicTournament;
  busy: boolean;
  error: unknown;
  onPlay: (nickname: string) => void;
}) {
  const timing = useWindow(card.opensAt, card.closesAt);
  const [nickname, setNickname] = useState("");
  const [withAccount, setWithAccount] = useState(false);

  if (withAccount) {
    return (
      <>
        <AuthForm intent="play" />
        <button type="button" className="link-button" onClick={() => setWithAccount(false)}>
          Play with just a name instead
        </button>
      </>
    );
  }

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (nickname.trim()) onPlay(nickname.trim());
  };

  return (
    <form className="guest" onSubmit={submit}>
      <Field
        label="Your name"
        hint="This is what the leaderboard shows. No account needed."
        value={nickname}
        onChange={(e) => setNickname(e.target.value)}
        required
        maxLength={40}
        autoComplete="nickname"
      />
      <ErrorNote error={error} />
      <Button type="submit" busy={busy} disabled={!timing.open || !nickname.trim()}>
        {timing.closed ? "Closed" : timing.open ? "Start playing" : "Not open yet"}
      </Button>
      <button type="button" className="link-button guest-alt" onClick={() => setWithAccount(true)}>
        I have an account
      </button>
    </form>
  );
}

function PlayButton({ card, busy, onPlay }: { card: PublicTournament; busy: boolean; onPlay: () => void }) {
  const timing = useWindow(card.opensAt, card.closesAt);
  return (
    <Button type="button" busy={busy} disabled={!timing.open} onClick={onPlay}>
      {timing.closed ? "Closed" : timing.open ? "Start playing" : "Not open yet"}
    </Button>
  );
}

/** Seconds left on the attempt's own clock, which the server set when it started. */
function useCountdown(expiresAt: string): number {
  const [left, setLeft] = useState(() => Math.max(0, Date.parse(expiresAt) - Date.now()));
  useEffect(() => {
    const timer = setInterval(() => setLeft(Math.max(0, Date.parse(expiresAt) - Date.now())), 250);
    return () => clearInterval(timer);
  }, [expiresAt]);
  return Math.ceil(left / 1000);
}

function Round({
  tournamentId,
  card,
  attempt,
  onFinish,
}: {
  tournamentId: string;
  card: PublicTournament;
  attempt: Saved;
  onFinish: (result: AttemptResult | null, workspaceId: string, message?: string) => void;
}) {
  const [position, setPosition] = useState(attempt.position);
  const [question, setQuestion] = useState<PlayableQuestion | null>(null);
  const [typed, setTyped] = useState("");
  const [feedback, setFeedback] = useState<{ answer: string; correct: boolean } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const seconds = useCountdown(attempt.expiresAt);
  const timed = card.timeLimitSeconds != null;

  const submit = useCallback(async () => {
    try {
      onFinish(await api.submit(attempt.attemptId), attempt.workspaceId);
    } catch (err) {
      // An attempt that ran out of time is graded by the server on its own.
      onFinish(null, attempt.workspaceId, err instanceof Error ? err.message : undefined);
    }
  }, [attempt, onFinish]);

  useEffect(() => {
    setQuestion(null);
    setTyped("");
    setFeedback(null);
    api.question(attempt.attemptId, position).then(setQuestion, setError);
  }, [attempt.attemptId, position]);

  useEffect(() => {
    if (timed && seconds === 0) void submit();
  }, [timed, seconds, submit]);

  const answer = async (value: string) => {
    if (feedback || !value.trim()) return;
    try {
      const result = await api.answer(attempt.attemptId, position, value.trim());
      setFeedback({ answer: value, correct: result.correct });
      const next = position + 1;
      save(tournamentId, { ...attempt, position: next });
      // Long enough to register right or wrong, short enough not to drag.
      setTimeout(() => (result.hasNext ? setPosition(next) : void submit()), 900);
    } catch (err) {
      setError(err);
    }
  };

  const onType = (e: FormEvent) => {
    e.preventDefault();
    void answer(typed);
  };

  return (
    <section className="round">
      <div className="round-head">
        <span>
          Question {position} of {attempt.total}
        </span>
        {timed && (
          <span className={seconds <= 30 ? "clock clock-low" : "clock"} aria-live="off">
            {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, "0")}
          </span>
        )}
      </div>
      <div className="progress" aria-hidden="true">
        <div style={{ width: `${((position - 1) / attempt.total) * 100}%` }} />
      </div>
      <ErrorNote error={error} />
      {question && (
        <>
          <h1 className="prompt">{question.prompt}</h1>
          {question.type === "SHORT_TEXT" || question.type === "NUMERIC" ? (
            <form className="typed" onSubmit={onType}>
              <input
                autoFocus
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                inputMode={question.type === "NUMERIC" ? "decimal" : "text"}
                aria-label="Your answer"
                disabled={!!feedback}
              />
              <Button type="submit" disabled={!!feedback || !typed.trim()}>
                Answer
              </Button>
            </form>
          ) : (
            <div className="choices">
              {question.options.map((option) => {
                const picked = feedback?.answer === option;
                const state = picked ? (feedback!.correct ? " choice-right" : " choice-wrong") : "";
                return (
                  <button key={option} type="button" className={`choice${state}`} disabled={!!feedback} onClick={() => void answer(option)}>
                    {option}
                  </button>
                );
              })}
            </div>
          )}
          <p className="verdict" aria-live="polite">
            {feedback ? (feedback.correct ? "Correct" : "Not this time") : ""}
          </p>
        </>
      )}
    </section>
  );
}

/** "you have already attempted this" -> "You have already attempted this." */
function sentence(text: string): string {
  const t = text.trim();
  if (!t) return t;
  const cased = t[0]!.toUpperCase() + t.slice(1);
  return /[.!?]$/.test(cased) ? cased : `${cased}.`;
}

function ordinal(n: number): string {
  const s = ["th", "st", "nd", "rd"];
  const v = n % 100;
  return n + (s[(v - 20) % 10] ?? s[v] ?? s[0]!);
}

function Result({
  tournamentId,
  card,
  result,
  you,
  workspaceId,
  message,
  exhausted,
}: {
  tournamentId: string;
  card: PublicTournament;
  result: AttemptResult | null;
  you: string;
  workspaceId: string;
  message?: string;
  exhausted?: boolean;
}) {
  const [standings, setStandings] = useState<Standing[] | null>(null);
  useEffect(() => {
    selectWorkspace(workspaceId);
    // Grading and ranking happen as the attempt is submitted; a short pause
    // lets the board catch up before it is first read.
    const read = () => api.standings(tournamentId).then(setStandings, () => setStandings([]));
    const first = setTimeout(read, 400);
    const timer = setInterval(read, 15_000);
    return () => {
      clearTimeout(first);
      clearInterval(timer);
    };
  }, [tournamentId, workspaceId]);

  const mine = standings?.find((s) => s.accountId === you);
  return (
    <section className="result">
      <p className="organiser">{card.name}</p>
      {result ? (
        <h1 className="display">
          {result.score} of {result.outOf}
        </h1>
      ) : (
        <>
          <h1 className="title">
            {exhausted ? "You've played this one" : message ? "Your results so far" : "Your attempt is in."}
          </h1>
          {exhausted ? (
            <p className="lede">
              {card.maxAttempts === 1
                ? "Everyone gets one attempt, and yours is in."
                : `You've used all ${card.maxAttempts} attempts. Your best one counts.`}
            </p>
          ) : (
            message && <p className="lede">{sentence(message)}</p>
          )}
        </>
      )}
      {mine && (
        <p className="lede">
          You're {ordinal(mine.rank)} of {standings!.length} so far. The board keeps filling in until the tournament closes.
        </p>
      )}
      {standings && <Board rows={standings} you={you} />}
    </section>
  );
}
