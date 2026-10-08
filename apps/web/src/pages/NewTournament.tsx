import { useEffect, useState, type FormEvent } from "react";
import { api, archiveIfUnused, selectWorkspace, type Question, type QuestionPayload, type QuestionType, type Tournament } from "../api/client";
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

type QuestionRequest = { type: QuestionType; prompt: string; payload: QuestionPayload };

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
function toRequest(d: Draft, n: number): QuestionRequest {
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

/**
 * A stored question as a draft, or null when it holds something this builder
 * cannot write back unchanged. Saving would otherwise quietly rewrite it.
 */
function fromQuestion(q: Question): Draft | null {
  const p = q.payload;
  if ((q.type === "SINGLE_CHOICE" || q.type === "TRUE_FALSE") && p.kind === "choice") {
    const texts = p.options.map((o) => o.text);
    if (p.options.filter((o) => o.correct).length !== 1) return null;
    if (q.type === "TRUE_FALSE" && texts.join("|") !== "True|False") return null;
    if (q.type === "SINGLE_CHOICE" && (texts.length < 2 || texts.length > 6)) return null;
    return {
      key: nextKey++,
      kind: q.type === "TRUE_FALSE" ? "truefalse" : "choice",
      prompt: q.prompt,
      options: texts,
      correct: p.options.findIndex((o) => o.correct),
      accepted: "",
    };
  }
  if (q.type === "SHORT_TEXT" && p.kind === "shortText" && p.ignoreCase) {
    return { key: nextKey++, kind: "text", prompt: q.prompt, options: ["", ""], correct: 0, accepted: p.accepted.join(" | ") };
  }
  return null;
}

const DAY = 86_400_000;
const DAYS = [1, 3, 7, 14];
const LIMITS = [0, 300, 600, 1200, 1800];
const ATTEMPTS = [1, 2, 3];

const daysLabel = (d: number) => (d === 1 ? "1 day" : d === 7 ? "1 week" : d === 14 ? "2 weeks" : `${d} days`);
const limitLabel = (s: number) => (s === 0 ? "No limit" : s % 60 === 0 ? `${s / 60} minutes` : `${s} seconds`);
const attemptsLabel = (n: number) =>
  n === 1 ? "One" : n === 2 ? "Two, best counts" : n === 3 ? "Three, best counts" : `${n}, best counts`;
/** The standard choices, plus whatever an existing tournament already uses. */
const withCurrent = (choices: number[], current: number) =>
  choices.includes(current) ? choices : [...choices, current].sort((a, b) => a - b);

/** The workspace this person organises in, made on first use. */
async function ownWorkspace(displayName: string): Promise<void> {
  const workspaces = await api.workspaces();
  const own = workspaces.find((w) => ["OWNER", "ADMIN", "EDITOR"].includes(w.role ?? ""));
  const workspace = own ?? (await api.createWorkspace(`${displayName}'s quizzes`));
  selectWorkspace(workspace.id);
}

/** Writes a fresh bank holding these questions. A bank per tournament keeps each quiz self-contained. */
async function writeBank(name: string, requests: QuestionRequest[]): Promise<string> {
  // The suffix keeps names unique, which the API requires within a workspace.
  const bank = await api.createBank(`${name} (${new Date().toISOString().slice(0, 16).replace("T", " ")})`);
  for (const request of requests) await api.authorQuestion(bank.id, request);
  return bank.id;
}

interface Original {
  tournament: Tournament;
  requests: QuestionRequest[];
}

export function NewTournament() {
  return <Builder />;
}

export function EditTournament({ id }: { id: string }) {
  return <Builder id={id} />;
}

function Builder({ id }: { id?: string }) {
  const editing = id !== undefined;
  const { account } = useSession();
  const [name, setName] = useState("");
  const [questions, setQuestions] = useState<Draft[]>(editing ? [] : [blank()]);
  // Creating: days open from now. Editing: 0 keeps the closing time as it is.
  const [closes, setCloses] = useState(editing ? 0 : 7);
  const [limit, setLimit] = useState(600);
  const [attempts, setAttempts] = useState(1);
  const [guests, setGuests] = useState(true);
  const [original, setOriginal] = useState<Original | null>(null);
  const [locked, setLocked] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (account === null || account?.guest) navigate("/sign-in", true);
  }, [account]);

  useEffect(() => {
    if (!id || !account || account.guest) return;
    (async () => {
      await ownWorkspace(account.displayName);
      const [t, plays] = await Promise.all([api.tournament(id), api.playCount(id)]);
      if (plays > 0) {
        setLocked("Someone has played this tournament, so its questions and settings are fixed now.");
        return;
      }
      // The listing is newest first. A lineage id is the id of version 1, and
      // ids are time-ordered, so sorting by it restores the order they were written.
      const stored = t.bankId ? await api.questions(t.bankId) : [];
      stored.sort((x, y) => (x.lineageId < y.lineageId ? -1 : x.lineageId > y.lineageId ? 1 : 0));
      const drafts = t.bankId ? stored.map(fromQuestion) : [null];
      if (drafts.length === 0 || drafts.some((d) => d === null)) {
        setLocked("This tournament has questions the editor can't change yet, such as ones with several right answers or a number for an answer.");
        return;
      }
      const loaded = drafts as Draft[];
      setName(t.name);
      setQuestions(loaded);
      setLimit(t.timeLimitSeconds ?? 0);
      setAttempts(t.maxAttempts);
      setGuests(t.allowGuests ?? false);
      setOriginal({ tournament: t, requests: loaded.map((d, i) => toRequest(d, i + 1)) });
    })().catch(setError);
  }, [id, account]);

  const update = (key: number, change: Partial<Draft>) =>
    setQuestions((qs) => qs.map((q) => (q.key === key ? { ...q, ...change } : q)));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    let requests: QuestionRequest[];
    try {
      if (!name.trim()) throw new Error("Give the tournament a name.");
      requests = questions.map((q, i) => toRequest(q, i + 1));
    } catch (err) {
      setError(err);
      return;
    }

    setBusy(true);
    try {
      if (original) await save(original, requests);
      else await create(requests);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  const create = async (requests: QuestionRequest[]) => {
    await ownWorkspace(account!.displayName);
    const bankId = await writeBank(name.trim(), requests);
    const now = Date.now();
    const tournament = await api.createTournament({
      name: name.trim(),
      bankId,
      opensAt: new Date(now).toISOString(),
      closesAt: new Date(now + closes * DAY).toISOString(),
      questions: requests.length,
      maxAttempts: attempts,
      ...(limit > 0 ? { timeLimitSeconds: limit } : {}),
      scoringPolicy: "BEST",
      allowGuests: guests,
    });
    navigate(`/app/t/${tournament.id}`);
  };

  /**
   * Changed questions go into a fresh bank, and the tournament moves onto it in
   * one request. Revising the old bank question by question would leave a
   * half-edited quiz if anything failed part way, and anyone starting in
   * between would play a mixture.
   */
  const save = async ({ tournament: t, requests: before }: Original, requests: QuestionRequest[]) => {
    const questionsChanged = JSON.stringify(requests) !== JSON.stringify(before);
    const bankId = questionsChanged ? await writeBank(name.trim(), requests) : t.bankId!;
    // An API-made tournament may draw fewer questions than its bank holds; keep that.
    const drawn = t.questions === before.length ? requests.length : Math.min(t.questions, requests.length);
    const from = Math.max(Date.now(), Date.parse(t.opensAt));
    try {
      await api.updateTournament(t.id, {
        name: name.trim(),
        bankId,
        opensAt: t.opensAt,
        closesAt: closes === 0 ? t.closesAt : new Date(from + closes * DAY).toISOString(),
        questions: questionsChanged ? drawn : t.questions,
        maxAttempts: attempts,
        ...(limit > 0 ? { timeLimitSeconds: limit } : {}),
        scoringPolicy: t.scoringPolicy ?? "BEST",
        allowGuests: guests,
      });
    } catch (err) {
      // Someone may have started playing meanwhile. The new bank is unused.
      if (questionsChanged) void archiveIfUnused(bankId);
      throw err;
    }
    if (questionsChanged && t.bankId) await archiveIfUnused(t.bankId);
    navigate(`/app/t/${t.id}`);
  };

  const back = editing ? `/app/t/${id}` : "/app";
  const closed = original ? Date.parse(original.tournament.closesAt) < Date.now() : false;

  if (locked) {
    return (
      <Shell>
        <Link href={back} className="back">
          Back to the tournament
        </Link>
        <h1 className="title">Edit tournament</h1>
        <p className="lede">{locked}</p>
      </Shell>
    );
  }

  if (editing && !original) {
    return (
      <Shell>
        <Link href={back} className="back">
          Back to the tournament
        </Link>
        <ErrorNote error={error} />
        {!error && <p className="muted">Loading…</p>}
      </Shell>
    );
  }

  return (
    <Shell>
      <Link href={back} className="back">
        {editing ? "Back to the tournament" : "Your tournaments"}
      </Link>
      <h1 className="title">{editing ? "Edit tournament" : "New tournament"}</h1>
      {editing && <p className="lede">Nobody has played it yet, so anything can change. The link stays the same.</p>}
      <form className="builder" onSubmit={submit}>
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
            <span className="field-label">{editing ? "Closes" : "Open for"}</span>
            <select value={closes} onChange={(e) => setCloses(Number(e.target.value))}>
              {editing && <option value={0}>{closed ? "Keep it closed" : "When it does now"}</option>}
              {DAYS.map((d) => (
                <option key={d} value={d}>
                  {editing ? `${daysLabel(d)} from now` : daysLabel(d)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Time to answer</span>
            <select value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
              {withCurrent(LIMITS, limit).map((s) => (
                <option key={s} value={s}>
                  {limitLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Attempts per player</span>
            <select value={attempts} onChange={(e) => setAttempts(Number(e.target.value))}>
              {withCurrent(ATTEMPTS, attempts).map((n) => (
                <option key={n} value={n}>
                  {attemptsLabel(n)}
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
          {editing ? "Save changes" : "Create and get the link"}
        </Button>
      </form>
    </Shell>
  );
}
