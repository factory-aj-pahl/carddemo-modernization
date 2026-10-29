import { type ReactNode, useEffect, useState } from 'react';
import type { MessageColor } from './api';
import { useSession } from './session';
import { type KeyLegend, SCREENS } from './screens';

const FKEY = /^F([1-9]|1[0-2])$/;

function pad2(n: number) {
  return String(n).padStart(2, '0');
}

function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);
  const text = `${pad2(now.getMonth() + 1)}/${pad2(now.getDate())}/${String(now.getFullYear()).slice(2)}  ${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())}`;
  return (
    <span className="clock" aria-label="Date and time">
      {text}
    </span>
  );
}

interface Props {
  program: string;
  message?: string;
  messageColor?: MessageColor;
  /** Called for ENTER and every F1-F12 press (legend or keyboard). */
  onKey: (key: string) => void;
  keys?: KeyLegend[];
  tran?: string;
  busy?: boolean;
  children: ReactNode;
}

/** Header, main area, message bar, and key bar shared by every screen (D-UI-LOOK). */
export function ScreenFrame({ program, message, messageColor, onKey, keys, tran, busy, children }: Props) {
  const info = SCREENS[program];
  const legend = keys ?? info?.keys ?? [];
  const user = useSession();
  const [pressed, setPressed] = useState<string | null>(null);

  useEffect(() => {
    // As on the 3270, ENTER is an AID key from any cursor position, including protected fields, the page
    // background, and screens or states with no visible ENTER button. Buttons, links, selects and text areas
    // keep their own Enter; an input in a form that has a submit button submits it natively.
    const enter = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.repeat || e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) return;
      const target = e.target instanceof Element ? e.target : null;
      if (target?.closest('button, a, select, textarea')) return;
      const form = target instanceof HTMLInputElement ? target.form : document.querySelector<HTMLFormElement>('main form');
      if (target instanceof HTMLInputElement && form?.querySelector('button[type="submit"]')) return;
      e.preventDefault();
      if (form) form.requestSubmit();
      else onKey('ENTER');
    };
    const down = (e: KeyboardEvent) => {
      if (e.key === 'Enter') {
        enter(e);
        return;
      }
      if (!FKEY.test(e.key) || e.ctrlKey || e.altKey || e.metaKey) return;
      e.preventDefault();
      setPressed(e.key);
      setTimeout(() => setPressed(null), 140);
      onKey(e.key);
    };
    document.addEventListener('keydown', down);
    return () => document.removeEventListener('keydown', down);
  }, [onKey]);

  const kind = !message ? '' : messageColor === 'GREEN' ? 'success' : messageColor === 'RED' ? 'error' : 'info';
  return (
    <>
      <a className="skip" href="#main">
        Skip to content
      </a>
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <span className="brand-name">CardDemo</span>
          <span className="brand-sub">AWS Mainframe Modernization</span>
        </div>
        <div className="header-meta">
          <span className="tran" title="Transaction · Program" data-testid="tran">
            {(tran ?? info?.tran ?? '') + ' · ' + program}
          </span>
          <span className="user" data-testid="user">
            {user ? (
              <>
                <span className="avatar" aria-hidden="true">
                  {user.userId.slice(0, 2)}
                </span>
                <span className="mono">{user.userId}</span>
                <span className="role">{user.role === 'ADMIN' ? 'Admin' : 'User'}</span>
              </>
            ) : (
              <span style={{ color: 'var(--subtle)' }}>Not signed in</span>
            )}
          </span>
          <Clock />
        </div>
      </header>
      <main id="main" tabIndex={-1} aria-busy={busy ? true : undefined} data-ready={busy ? undefined : ''}>
        {children}
      </main>
      <footer className="app-footer">
        <div className={`msgbar ${kind}`} role="status" aria-live="polite" data-testid="message">
          {message ? (
            <>
              <span className="icon" aria-hidden="true">
                {kind === 'success' ? '✓' : kind === 'info' ? 'i' : '!'}
              </span>
              <span className="msg-text">{message}</span>
            </>
          ) : null}
        </div>
        <nav className="keybar" aria-label="Function keys">
          {legend.map((k) => (
            <button
              key={k.key}
              type="button"
              className={`key${k.key === 'ENTER' ? ' primary' : ''}${pressed === k.key ? ' pressed' : ''}`}
              aria-keyshortcuts={k.key === 'ENTER' ? 'Enter' : k.key}
              onClick={() => onKey(k.key)}
            >
              <kbd>{k.key}</kbd>
              <span>{k.label}</span>
            </button>
          ))}
        </nav>
      </footer>
    </>
  );
}
