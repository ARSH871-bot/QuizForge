import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { match, usePath } from "./router";
import { SessionProvider } from "./session";
import { Home } from "./pages/Home";
import { NewTournament } from "./pages/NewTournament";
import { Organiser, SignIn, TournamentAdmin } from "./pages/Organiser";
import { Play } from "./pages/Play";
import { ForgotPassword, ResetPassword } from "./pages/Password";
import { Shell } from "./ui";
import { Link } from "./router";
import "./styles.css";

function App() {
  const path = usePath();
  let params: Record<string, string> | null;
  if (path === "/") return <Home />;
  if (path === "/sign-in") return <SignIn />;
  if (path === "/forgot-password") return <ForgotPassword />;
  if (path === "/reset-password") return <ResetPassword />;
  if (path === "/app") return <Organiser />;
  if (path === "/app/new") return <NewTournament />;
  if ((params = match("/app/t/:id", path))) return <TournamentAdmin key={params.id} id={params.id!} />;
  if ((params = match("/t/:id", path))) return <Play key={params.id} id={params.id!} />;
  return (
    <Shell>
      <h1 className="title">There's nothing at this address</h1>
      <p className="lede">If someone sent you a link to a tournament, check it was copied in full.</p>
      <Link href="/" className="button button-primary">
        Go to QuizForge
      </Link>
    </Shell>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <SessionProvider>
      <App />
    </SessionProvider>
  </StrictMode>,
);
