import { useEffect, useState, type FormEvent } from "react";
import { api, selectWorkspace, type QuestionPayload, type QuestionType } from "../api/client";
import { Link, navigate } from "../router";
import { useSession } from "../session";
import { Button, ErrorNote, Field, Shell } from "../ui";

type Kind = "choice" | "truefalse" | "text";

interface Draft {
  key: number;
  kind: Kind;
  prompt: string;
  options: string[];
  correct: number;
  accepted: string;
}

let nextKey = 1;
const blank = (kind: Kind = "choice"): Draft => ({
  key: nextKey++,
  kind,
  prompt: "",
  options: kind === "truefalse" ? ["True", "False"] : ["", ""],
  correct: 0,
  accepted: "",
});

/** What the API needs for each draft, or a sentence saying what is missing. */
function toRequest(d: Draft, n: number): { type: QuestionType; prompt: string; payload: QuestionPayload } {
  const prompt = d.prompt.trim();
  if (!prompt) throw new Error(`Question ${n} needs a question.`);
  if (d.kind === "text") {
    const accepted = d.accepted.split("|").map((a) => a.trim()).filter(Boolean);
    if (accepted.length === 0) throw new Error(`Question ${n} needs at least one accepted answer.`);
    return { type: "SHORT_TEXT", prompt, payload: { kind: "shortText", accepted, ignoreCase: true } as QuestionPayload };
  }
  const options = d.options.map((o) => o.trim());
  if (options.some((o) => !o)) throw new Error(`Question ${n} has an empty option.`);
  if (new Set(options.map((o) => o.toLowerCase())).size !== options.length)
    throw new Error(`Question ${n} has two options that are the same.`);
  return {
    type: d.kind === "truefalse" ? "TRUE_FALSE" : "SINGLE_CHOICE",
    prompt,
    payload: { kind: "choice", options: options.map((text, i) => ({ text, correct: i === d.correct })) },
  };
}

const DAYS = [1, 3, 7, 14];
const LIMITS = [0, 5, 10, 20, 30];

export function NewTournament() {
  const { account } = useSession();
  const [name, setName] = useState("");
  const [questions, setQuestions] = useState<Draft[]>([blank()]);
  const [days, setDays] = useState(7);
  const [minutes, setMinutes] = useState(10);
  const [attempts, setAttempts] = useState(1);
  const [guests, setGuests] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (account === null) navigate("/sign-in", true);
  }, [account]);

  const update = (key: number, change: Partial<Draft>) =>
    setQuestions((qs) => qs.map((q) => (q.key === key ? { ...q, ...change } : q)));

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    let requests;
    try {
      if (!name.trim()) throw new Error("Give the tournament a name.");
      requests = questions.map((q, i) => toRequest(q, i + 1));
    } catch (err) {
      setError(err);
      return;
    }

    setBusy(true);
    try {
      const workspaces = await api.workspaces();
      const own = workspaces.find((w) => ["OWNER", "ADMIN", "EDITOR"].includes(w.role ?? ""));
      const workspace = own ?? (await api.createWorkspace(`${account!.displayName}'s quizzes`));
      selectWorkspace(workspace.id);

      // A bank per tournament keeps each quiz self-contained. The suffix keeps
      // names unique, which the API requires within a workspace.
      const bank = await api.createBank(`${name.trim()} (${new Date().toISOString().slice(0, 16).replace("T", " ")})`);
      for (const request of requests) await api.authorQuestion(bank.id, request);

      const now = Date.now();
      const tournament = await api.createTournament({
        name: name.trim(),
        bankId: bank.id,
        opensAt: new Date(now).toISOString(),
        closesAt: new Date(now + days * 86_400_000).toISOString(),
        questions: requests.length,
        maxAttempts: attempts,
        ...(minutes > 0 ? { timeLimitSeconds: minutes * 60 } : {}),
        scoringPolicy: "BEST",
        allowGuests: guests,
      });
      navigate(`/app/t/${tournament.id}`);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  return (
    <Shell>
      <Link href="/app" className="back">
        Your tournaments
      </Link>
      <h1 className="title">New tournament</h1>
      <form className="builder" onSubmit={create}>
        <Field label="Name" placeholder="Friday geography quiz" value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />

        <ol className="questions">
          {questions.map((q, i) => (
            <li key={q.key} className="question">
              <div className="question-head">
                <span className="question-number">Question {i + 1}</span>
                <select
                  aria-label={`Question ${i + 1} type`}
                  value={q.kind}
                  onChange={(e) => {
                    const kind = e.target.value as Kind;
                    update(q.key, { kind, options: kind === "truefalse" ? ["True", "False"] : q.kind === "truefalse" ? ["", ""] : q.options, correct: 0 });
                  }}
                >
                  <option value="choice">Multiple choice</option>
                  <option value="truefalse">True or false</option>
                  <option value="text">Type the answer</option>
                </select>
                {questions.length > 1 && (
                  <button type="button" className="link-button" onClick={() => setQuestions((qs) => qs.filter((x) => x.key !== q.key))}>
                    Remove
                  </button>
                )}
              </div>
              <textarea
                aria-label={`Question ${i + 1}`}
                placeholder="What is the capital of Australia?"
                value={q.prompt}
                onChange={(e) => update(q.key, { prompt: e.target.value })}
                rows={2}
                maxLength={4000}
              />
              {q.kind === "text" ? (
                <Field
                  label="Accepted answers"
                  hint="Separate alternatives with |, for example Nile | The Nile. Capitals don't matter."
                  value={q.accepted}
                  onChange={(e) => update(q.key, { accepted: e.target.value })}
                />
              ) : (
                <fieldset className="options">
                  <legend>Options. Select the correct one.</legend>
                  {q.options.map((option, oi) => (
                    <div key={oi} className="option">
                      <input
                        type="radio"
                        name={`correct-${q.key}`}
                        checked={q.correct === oi}
                        onChange={() => update(q.key, { correct: oi })}
                        aria-label={`Option ${oi + 1} is correct`}
                      />
                      <input
                        type="text"
                        value={option}
                        readOnly={q.kind === "truefalse"}
                        placeholder={`Option ${oi + 1}`}
                        aria-label={`Option ${oi + 1}`}
                        onChange={(e) => update(q.key, { options: q.options.map((o, j) => (j === oi ? e.target.value : o)) })}
                      />
                      {q.kind === "choice" && q.options.length > 2 && (
                        <button
                          type="button"
                          className="link-button"
                          aria-label={`Remove option ${oi + 1}`}
                          onClick={() =>
                            update(q.key, {
                              options: q.options.filter((_, j) => j !== oi),
                              correct: q.correct === oi ? 0 : q.correct > oi ? q.correct - 1 : q.correct,
                            })
                          }
                        >
                          Remove
                        </button>
                      )}
                    </div>
                  ))}
                  {q.kind === "choice" && q.options.length < 6 && (
                    <button type="button" className="link-button" onClick={() => update(q.key, { options: [...q.options, ""] })}>
                      Add an option
                    </button>
                  )}
                </fieldset>
              )}
            </li>
          ))}
        </ol>
        <Button type="button" variant="quiet" onClick={() => setQuestions((qs) => [...qs, blank(qs[qs.length - 1]?.kind)])}>
          Add a question
        </Button>

        <div className="settings">
          <label className="field">
            <span className="field-label">Open for</span>
            <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
              {DAYS.map((d) => (
                <option key={d} value={d}>
                  {d === 1 ? "1 day" : d === 7 ? "1 week" : d === 14 ? "2 weeks" : `${d} days`}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Time to answer</span>
            <select value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}>
              {LIMITS.map((m) => (
                <option key={m} value={m}>
                  {m === 0 ? "No limit" : `${m} minutes`}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Attempts per player</span>
            <select value={attempts} onChange={(e) => setAttempts(Number(e.target.value))}>
              {[1, 2, 3].map((n) => (
                <option key={n} value={n}>
                  {n === 1 ? "One" : n === 2 ? "Two, best counts" : "Three, best counts"}
                </option>
              ))}
            </select>
          </label>
        </div>

        <label className="check">
          <input type="checkbox" checked={guests} onChange={(e) => setGuests(e.target.checked)} />
          <span>
            <span className="check-label">Let people play with just a name</span>
            <span className="field-hint">
              No account needed, so more people play. Someone could play again from another browser under a new name,
              so turn this off for anything that counts.
            </span>
          </span>
        </label>

        <ErrorNote error={error} />
        <Button type="submit" busy={busy}>
          Create and get the link
        </Button>
      </form>
    </Shell>
  );
}
