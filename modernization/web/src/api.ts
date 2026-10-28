/** Client for the screen API (/api/screens/<PROGRAM>): one GET for first entry, one POST per screen turn. */

export type Key = 'ENTER' | 'CLEAR' | 'PA1' | 'PA2' | 'PA3' | `PF${number}`;
export type MessageColor = 'RED' | 'GREEN' | 'NEUTRAL';

export interface ScreenResponse<F> {
  fields?: F;
  message?: string;
  messageColor?: MessageColor;
  focus?: string;
  navigate?: string;
}

export interface SessionUser {
  userId: string;
  role: 'ADMIN' | 'USER';
  firstName: string;
  lastName: string;
}

/** The server has no valid session for this request; the app shows a clean sign-on screen. */
export class Unauthorized extends Error {
  constructor() {
    super('unauthorized');
  }
}

function csrfToken(): string | undefined {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : undefined;
}

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method === 'POST') {
    headers['Content-Type'] = 'application/json';
    const token = csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) throw new Unauthorized();
  // A USER calling an ADMIN screen gets 403 with a navigate-to-menu screen response.
  if (res.status === 403 && res.headers.get('Content-Type')?.includes('json')) return (await res.json()) as T;
  if (!res.ok) throw new Error(`${method} ${path} failed with HTTP ${res.status}`);
  return (await res.json()) as T;
}

export function showScreen<F>(program: string): Promise<ScreenResponse<F>> {
  return call('GET', `/api/screens/${program}`);
}

export function screenTurn<F>(program: string, key: Key, input: object): Promise<ScreenResponse<F>> {
  return call('POST', `/api/screens/${program}`, { ...input, key });
}

export async function currentSession(): Promise<SessionUser | null> {
  try {
    return await call<SessionUser>('GET', '/api/session');
  } catch (e) {
    if (e instanceof Unauthorized) return null;
    throw e;
  }
}
