import { useEffect, useState, type ButtonHTMLAttributes, type FormEvent, type InputHTMLAttributes, type ReactNode } from "react";
import { ApiError, type Standing } from "./api/client";
import { Link } from "./router";
import { useSession } from "./session";

export function Shell({ children, wide = false }: { children: ReactNode; wide?: boolean }) {
  const { account, signOut } = useSession();
  return (
    <>
      <header className="topbar">
        <Link href={account && !account.guest ? "/app" : "/"} className="wordmark">
          QuizForge
        </Link>
        <nav>
          {account ? (
            <>
              <span className="topbar-name">
                {account.displayName}
                {account.guest && <span className="topbar-guest"> (guest)</span>}
              </span>
              <button type="button" className="link-button" onClick={() => void signOut()}>
                Sign out
              </button>
            </>
          ) : (
            <Link href="/sign-in">Sign in</Link>
          )}
        </nav>
      </header>
      <main className={wide ? "page page-wide" : "page"}>{children}</main>
    </>
  );
}

export function Button({ busy, children, variant = "primary", ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean; variant?: "primary" | "quiet" }) {
  return (
    <button className={`button button-${variant}`} disabled={busy || rest.disabled} {...rest}>
      {busy ? "Working…" : children}
    </button>
  );
}

export function Field({ label, hint, ...input }: InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      <input {...input} />
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof ApiError || error instanceof Error ? error.message : String(error);
  return (
    <p className="error" role="alert">
      {message}
    </p>
  );
}

/** "3 days", "5 hours", "12 minutes" — the largest unit that is at least one. */
function span(ms: number): string {
  const minutes = Math.max(1, Math.round(ms / 60000));
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"}`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} hour${hours === 1 ? "" : "s"}`;
  const days = Math.round(hours / 24);
  return `${days} days`;
}

/** Where the tournament is in its window, said plainly and kept current. */
export function useWindow(opensAt: string, closesAt: string): { open: boolean; closed: boolean; text: string } {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);
  const opens = Date.parse(opensAt);
  const closes = Date.parse(closesAt);
  if (now < opens) return { open: false, closed: false, text: `Opens in ${span(opens - now)}` };
  if (now < closes) return { open: true, closed: false, text: `Closes in ${span(closes - now)}` };
  return { open: false, closed: true, text: "This tournament has closed" };
}

/**
 * The leaderboard. The one bold element in the product: a scoreboard green,
 * with each player on their own white strip, because in an async tournament
 * the board fills in as people finish, like a golf leaderboard on the
 * final day.
 */
export function Board({ rows, you, empty }: { rows: Standing[]; you?: string; empty?: string }) {
  return (
    <section className="board" aria-label="Leaderboard">
      <div className="board-head">
        <span>Leaderboard</span>
        <span>Score</span>
      </div>
      {rows.length === 0 ? (
        <p className="board-empty">{empty ?? "Nobody has finished yet. The board fills in as players do."}</p>
      ) : (
        <ol className="board-rows">
          {rows.map((row) => {
            const mine = row.accountId === you;
            return (
              <li key={row.accountId} className={mine ? "board-row board-row-you" : "board-row"}>
                <span className="board-rank">{row.rank}</span>
                <span className="board-name">
                  {row.displayName ?? "Former player"}
                  {mine && <span className="board-you">You</span>}
                  {row.guest && !mine && <span className="board-guest">Guest</span>}
                </span>
                <span className="board-score">
                  {Number.isInteger(row.score) ? row.score : row.score.toFixed(1)}
                  <span className="board-outof">/{row.outOf}</span>
                </span>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}

/** Sign in or create an account without leaving the page the person is on. */
export function AuthForm({ intent, onDone }: { intent: string; onDone?: () => void }) {
  const { signIn, signUp } = useSession();
  const [mode, setMode] = useState<"new" | "existing">("new");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "new") await signUp(name.trim(), email.trim(), password);
      else await signIn(email.trim(), password);
      onDone?.();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="auth" onSubmit={submit}>
      <div className="auth-switch" role="tablist">
        <button type="button" role="tab" aria-selected={mode === "new"} onClick={() => setMode("new")}>
          New here
        </button>
        <button type="button" role="tab" aria-selected={mode === "existing"} onClick={() => setMode("existing")}>
          I have an account
        </button>
      </div>
      {mode === "new" && (
        <Field label="Your name" hint="This is what the leaderboard shows." value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} autoComplete="nickname" />
      )}
      <Field label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
      <Field
        label="Password"
        type="password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        required
        minLength={mode === "new" ? 12 : undefined}
        hint={mode === "new" ? "At least 12 characters." : undefined}
        autoComplete={mode === "new" ? "new-password" : "current-password"}
      />
      <ErrorNote error={error} />
      <Button type="submit" busy={busy}>
        {mode === "new" ? `Create account and ${intent}` : `Sign in and ${intent}`}
      </Button>
    </form>
  );
}
