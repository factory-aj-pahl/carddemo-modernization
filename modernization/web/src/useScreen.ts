import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { type Key, type MessageColor, type ScreenResponse, Unauthorized, screenTurn, showScreen } from './api';

/**
 * Messages the COBOL sends with the cursor on a field although they are not about that field's value:
 * invalid keys, paging boundaries, selection-code errors, menu availability, confirmation prompts,
 * whole-record conditions, and file errors. The field gets the cursor (focus) but is not marked in error.
 */
const NOT_ABOUT_FIELD = [
  /^Invalid key pressed/,
  /(reached|already at) the (top|bottom) of the page/,
  /^Invalid selection/,
  /is not installed/,
  /is coming soon/,
  /^Confirm to /,
  /^Please confirm to /,
  /^Unable to /,
  // COUSR02C: nothing changed, or the record changed since it was read (cursor on First Name).
  /^Please modify to update/,
  /^Record changed by some one else/,
  // A generated transaction ID that is already on file (COTRN02C, COBIL00C).
  /^Tran ID already exist/,
  // COPAUS0C: file and database errors, and a missing account or customer record behind a cross-reference
  // that was found. "not found in XREF file" is about the account typed and stays marked.
  /System error while/,
  /not found in (ACCT|CUST) file/,
];

/**
 * The server put the cursor on this field with a red message about its value: mark it in error.
 * `notAbout` adds messages that only this screen sends without concerning the cursor field.
 */
export function fieldInError(state: ScreenResponse<unknown> | null, id: string, notAbout: readonly RegExp[] = []): boolean {
  if (!state || state.messageColor !== 'RED' || state.focus !== id) return false;
  const text = (state.message ?? '').trim();
  return ![...NOT_ABOUT_FIELD, ...notAbout].some((re) => re.test(text));
}

/** A 3270 protected field: the cursor must not rest there in the browser. */
function isProtected(el: Element | null): boolean {
  return el instanceof HTMLElement && el.matches('input[readonly], textarea[readonly], :disabled');
}

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

  // Runs once the turn has finished (buttons enabled again). When the server names a protected field, or
  // names none while the old focus is protected or was lost (a focused button is disabled while the turn
  // runs, which drops focus to the body), focus goes to the form's submit button so that Enter
  // still sends the ENTER AID, as from any cursor position on the 3270. Focusing the primary button would
  // be wrong: in a confirm state it can be F5 Save, and Enter on it would save.
  // A layout effect, because the same commit marks the screen ready: keys typed as soon as it is ready
  // must already reach the cursor field.
  useLayoutEffect(() => {
    if (!state || busy) return;
    const el = state.focus ? document.getElementById(state.focus) : null;
    if (el && !isProtected(el)) {
      el.focus();
      if (el instanceof HTMLInputElement && state.messageColor === 'RED') el.select();
      return;
    }
    const active = document.activeElement;
    if (!el && active && active !== document.body && !isProtected(active)) return;
    // A selection left in a protected field still shows after focus moves on.
    for (const p of [el, active]) {
      if ((p instanceof HTMLInputElement || p instanceof HTMLTextAreaElement) && isProtected(p)) p.setSelectionRange(0, 0);
    }
    const submit = document.querySelector<HTMLElement>('main form button[type="submit"]:not(:disabled)');
    (submit ?? document.getElementById('main'))?.focus();
  }, [state, busy]);

  return { state, busy: busy || state === null, error, send };
}
