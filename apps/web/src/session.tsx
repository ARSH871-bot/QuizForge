import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, ApiError, selectWorkspace, type Account } from "./api/client";

interface Session {
  /** `undefined` while the first check is in flight; `null` when signed out. */
  account: Account | null | undefined;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (name: string, email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<Account | null | undefined>(undefined);

  useEffect(() => {
    api.me().then(setAccount, (e: unknown) => {
      if (e instanceof ApiError && e.status === 401) setAccount(null);
      else setAccount(null);
    });
  }, []);

  const signIn = useCallback(async (email: string, password: string) => {
    setAccount(await api.login(email, password));
  }, []);

  // Registering does not sign in — the API keeps the two apart — so this does both.
  const signUp = useCallback(async (name: string, email: string, password: string) => {
    await api.register(email, password, name);
    setAccount(await api.login(email, password));
  }, []);

  const signOut = useCallback(async () => {
    await api.logout().catch(() => undefined);
    selectWorkspace(null);
    setAccount(null);
  }, []);

  return <SessionContext.Provider value={{ account, signIn, signUp, signOut }}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) throw new Error("useSession outside SessionProvider");
  return session;
}
