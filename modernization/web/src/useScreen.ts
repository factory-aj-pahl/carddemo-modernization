import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { type Key, type MessageColor, type ScreenResponse, Unauthorized, screenTurn, showScreen } from './api';

export interface Flash {
  message?: string;
  messageColor?: MessageColor;
}

export interface Screen<F> {
  /** Latest screen state from the server; null until first entry has loaded. */
  state: ScreenResponse<F> | null;
  busy: boolean;
  error: string | null;
  send: (key: Key, input: object) => void;
}

/**
 * One pseudo-conversational screen: loads first entry, sends each key press as a turn, follows
 * navigate responses, and moves focus to the field the server names (the 3270 cursor).
 */
export function useScreen<F>(program: string): Screen<F> {
  const navigate = useNavigate();
  const location = useLocation();
  const [state, setState] = useState<ScreenResponse<F> | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const flash = useRef<Flash | null>((location.state as Flash | null) ?? null);
  // Like a 3270 keyboard lock: no key is sent until the first-entry screen has arrived, so a late
  // first-entry response can never overwrite the result of a turn.
  const inFlight = useRef(true);

  const apply = useCallback(
    (r: ScreenResponse<F>) => {
      if (r.navigate) {
        const carried: Flash = { message: r.message, messageColor: r.messageColor };
        if (r.navigate === 'EXIT') navigate('/signed-off', { state: carried });
        else navigate(`/screens/${r.navigate}`, { state: carried });
        return;
      }
      setState(r);
    },
    [navigate],
  );

  const fail = useCallback(
    (e: unknown) => {
      if (e instanceof Unauthorized) {
        navigate('/screens/COSGN00C', { replace: true, state: null });
        return;
      }
      setError(e instanceof Error ? e.message : String(e));
    },
    [navigate],
  );

  useEffect(() => {
    let live = true;
    inFlight.current = true;
    setState(null);
    setError(null);
    showScreen<F>(program)
      .then((r) => {
        if (!live) return;
        const f = flash.current;
        flash.current = null;
        inFlight.current = false;
        apply(f?.message ? { ...r, message: f.message, messageColor: f.messageColor } : r);
      })
      .catch((e) => live && fail(e));
    return () => {
      live = false;
    };
  }, [program, apply, fail]);

  const send = useCallback(
    (key: Key, input: object) => {
      if (inFlight.current) return;
      inFlight.current = true;
      setBusy(true);
      setError(null);
      screenTurn<F>(program, key, input)
        .then(apply)
        .catch(fail)
        .finally(() => {
          inFlight.current = false;
          setBusy(false);
        });
    },
    [program, apply, fail],
  );

  useEffect(() => {
    if (!state?.focus) return;
    const el = document.getElementById(state.focus);
    if (el instanceof HTMLInputElement) {
      el.focus();
      if (state.messageColor === 'RED') el.select();
    } else el?.focus();
  }, [state]);

  return { state, busy: busy || state === null, error, send };
}
