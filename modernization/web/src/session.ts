import { createContext, useContext, useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { type SessionUser, currentSession } from './api';

export const SessionContext = createContext<SessionUser | null>(null);

export function useSession() {
  return useContext(SessionContext);
}

/** Re-reads the signed-on user on every route change (sign-on, sign-off, and expiry all change routes). */
export function useSessionTracker(): SessionUser | null {
  const location = useLocation();
  const [user, setUser] = useState<SessionUser | null>(null);
  useEffect(() => {
    let live = true;
    currentSession()
      .then((u) => live && setUser(u))
      .catch(() => live && setUser(null));
    return () => {
      live = false;
    };
  }, [location.pathname]);
  return user;
}
