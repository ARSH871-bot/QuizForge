import { useState, type FormEvent } from "react";
import { api } from "../api/client";
import { Link } from "../router";
import { Button, ErrorNote, Field, Shell } from "../ui";

/** Asks for a reset link. Says the same thing whether or not the address has an account. */
export function ForgotPassword() {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.requestPasswordReset(email.trim());
      setSent(true);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Shell>
      <Link href="/sign-in" className="back">
        Sign in
      </Link>
      <h1 className="title">Reset your password</h1>
      {sent ? (
        <p className="lede">
          If there's an account for {email.trim()}, we've emailed it a link. It works once, for an hour. Check your spam
          folder if it hasn't arrived in a few minutes.
        </p>
      ) : (
        <form onSubmit={submit}>
          <p className="lede">Enter the email you signed up with and we'll send you a link to choose a new password.</p>
          <Field label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
          <ErrorNote error={error} />
          <Button type="submit" busy={busy}>
            Email me a link
          </Button>
        </form>
      )}
    </Shell>
  );
}

/** Where the emailed link lands. */
export function ResetPassword() {
  const token = new URLSearchParams(location.search).get("token") ?? "";
  const [password, setPassword] = useState("");
  const [again, setAgain] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (password !== again) {
      setError(new Error("The two passwords don't match."));
      return;
    }
    setBusy(true);
    try {
      await api.resetPassword(token, password);
      setDone(true);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  if (!token) {
    return (
      <Shell>
        <h1 className="title">This link is incomplete</h1>
        <p className="lede">Open the link from the email again, or ask for a new one.</p>
        <Link href="/forgot-password" className="button button-primary">
          Ask for a new link
        </Link>
      </Shell>
    );
  }

  return (
    <Shell>
      <h1 className="title">Choose a new password</h1>
      {done ? (
        <>
          <p className="lede">Your password is changed, and you've been signed out everywhere. Sign in with the new one.</p>
          <Link href="/sign-in" className="button button-primary">
            Sign in
          </Link>
        </>
      ) : (
        <form onSubmit={submit}>
          <Field
            label="New password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={12}
            hint="At least 12 characters."
            autoComplete="new-password"
          />
          <Field
            label="The same again"
            type="password"
            value={again}
            onChange={(e) => setAgain(e.target.value)}
            required
            minLength={12}
            autoComplete="new-password"
          />
          <ErrorNote error={error} />
          <Button type="submit" busy={busy}>
            Change password
          </Button>
          <p className="muted">
            Link not working? <Link href="/forgot-password">Ask for a new one</Link>.
          </p>
        </form>
      )}
    </Shell>
  );
}
