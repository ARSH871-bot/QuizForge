import { useEffect } from "react";
import { navigate, Link } from "../router";
import { useSession } from "../session";
import { Board, Shell } from "../ui";
import type { Standing } from "../api/client";

// Illustrative rows for the landing page only. Real boards come from the API.
const sample: Standing[] = [
  { accountId: "a1", displayName: "Mere Tane", score: 10, outOf: 10, attempts: 1, firstGradedAt: "", rank: 1 },
  { accountId: "a2", displayName: "Jonah Reid", score: 9, outOf: 10, attempts: 2, firstGradedAt: "", rank: 2 },
  { accountId: "a3", displayName: "Priya Nair", score: 9, outOf: 10, attempts: 1, firstGradedAt: "", rank: 3 },
  { accountId: "a4", displayName: "Sam Okafor", score: 7, outOf: 10, attempts: 1, firstGradedAt: "", rank: 4 },
];

export function Home() {
  const { account } = useSession();

  useEffect(() => {
    if (account) navigate("/app", true);
  }, [account]);

  return (
    <Shell wide>
      <section className="hero">
        <div className="hero-copy">
          <h1 className="display">Quiz tournaments people play on their own time.</h1>
          <p className="lede">
            Write the questions, set a window, share one link. Everyone plays when it suits them, and the leaderboard
            fills in as they finish.
          </p>
          <div className="hero-actions">
            <Link href="/sign-in" className="button button-primary">
              Create a tournament
            </Link>
          </div>
          <ul className="facts">
            <li>No app to install. Players open the link on any phone.</li>
            <li>Every attempt gets its own question order, so answers are harder to pass around.</li>
            <li>Answers are checked on the server and never sent to the player.</li>
          </ul>
        </div>
        <div className="hero-board">
          <Board rows={sample} />
          <p className="caption">Friday Geography Quiz, closes in 2 days</p>
        </div>
      </section>
    </Shell>
  );
}
