import { useEffect, useState } from "react";
import { api, selectWorkspace, type Tournament } from "../api/client";
import { Link, navigate } from "../router";
import { useSession } from "../session";
import { AuthForm, Board, Button, ErrorNote, Shell, useWindow } from "../ui";
import type { Standing } from "../api/client";

const ORGANISING = new Set(["OWNER", "ADMIN", "EDITOR"]);

/**
 * The workspace this person organises in. A first visit creates one, so nobody
 * has to understand workspaces before they can run a quiz.
 */
async function organiserWorkspace(displayName: string): Promise<string> {
  const mine = (await api.workspaces()).find((w) => ORGANISING.has(w.role ?? ""));
  const workspace = mine ?? (await api.createWorkspace(`${displayName}'s quizzes`));
  selectWorkspace(workspace.id);
  return workspace.id;
}

export function SignIn() {
  const { account } = useSession();
  useEffect(() => {
    if (account) navigate("/app", true);
  }, [account]);
  return (
    <Shell>
      <h1 className="title">Run a tournament</h1>
      <p className="lede">Create an account to write questions and share your first link.</p>
      <AuthForm intent="continue" onDone={() => navigate("/app")} />
    </Shell>
  );
}

function useOrganiser() {
  const { account } = useSession();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    if (account === null) navigate("/sign-in", true);
    if (!account) return;
    organiserWorkspace(account.displayName).then(() => setReady(true), setError);
  }, [account]);
  return { account, ready, error };
}

function TournamentRow({ t }: { t: Tournament }) {
  const timing = useWindow(t.opensAt, t.closesAt);
  return (
    <li>
      <Link href={`/app/t/${t.id}`} className="row">
        <span className="row-title">{t.name}</span>
        <span className={timing.open ? "row-meta row-meta-open" : "row-meta"}>{timing.text}</span>
      </Link>
    </li>
  );
}

export function Organiser() {
  const { ready, error } = useOrganiser();
  const [tournaments, setTournaments] = useState<Tournament[] | null>(null);

  useEffect(() => {
    if (ready) api.tournaments().then(setTournaments, () => setTournaments([]));
  }, [ready]);

  return (
    <Shell>
      <div className="title-row">
        <h1 className="title">Your tournaments</h1>
        <Link href="/app/new" className="button button-primary">
          New tournament
        </Link>
      </div>
      <ErrorNote error={error} />
      {tournaments === null ? (
        <p className="muted">Loading…</p>
      ) : tournaments.length === 0 ? (
        <div className="empty">
          <p>You haven't run a tournament yet. Write a few questions and you'll have a link to share in a couple of minutes.</p>
          <Link href="/app/new" className="button button-primary">
            Write your first tournament
          </Link>
        </div>
      ) : (
        <ul className="rows">
          {tournaments.map((t) => (
            <TournamentRow key={t.id} t={t} />
          ))}
        </ul>
      )}
    </Shell>
  );
}

export function TournamentAdmin({ id }: { id: string }) {
  const { account, ready, error: setupError } = useOrganiser();
  const [tournament, setTournament] = useState<Tournament | null>(null);
  const [standings, setStandings] = useState<Standing[]>([]);
  const [plays, setPlays] = useState<number | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (!ready) return;
    api.tournament(id).then(setTournament, setError);
    const refresh = () => {
      api.standings(id).then(setStandings, () => undefined);
      api.playCount(id).then(setPlays, () => undefined);
    };
    refresh();
    // The board fills in while people play, so keep it current while it is open.
    const timer = setInterval(refresh, 15_000);
    return () => clearInterval(timer);
  }, [ready, id]);

  const link = `${location.origin}/t/${id}`;
  const copy = async () => {
    await navigator.clipboard.writeText(link);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <Shell>
      <Link href="/app" className="back">
        Your tournaments
      </Link>
      <ErrorNote error={setupError ?? error} />
      {tournament && (
        <>
          <h1 className="title">{tournament.name}</h1>
          <AdminWindow t={tournament} plays={plays} />
          <section className="share">
            <h2 className="subtitle">Share this link</h2>
            <p className="muted">Anyone with it can join and play once they've signed up.</p>
            <div className="share-row">
              <input className="share-link" readOnly value={link} onFocus={(e) => e.currentTarget.select()} aria-label="Share link" />
              <Button type="button" onClick={() => void copy()}>
                {copied ? "Copied" : "Copy link"}
              </Button>
            </div>
          </section>
          <Board rows={standings} you={account?.id} />
        </>
      )}
    </Shell>
  );
}

function AdminWindow({ t, plays }: { t: Tournament; plays: number | null }) {
  const timing = useWindow(t.opensAt, t.closesAt);
  const played = plays === null ? "" : plays === 1 ? " One attempt so far." : ` ${plays} attempts so far.`;
  return (
    <p className="lede">
      {timing.text}. {t.questions} questions per attempt
      {t.timeLimitSeconds ? `, ${Math.round(t.timeLimitSeconds / 60)} minutes to answer them` : ""}.{played}
    </p>
  );
}
