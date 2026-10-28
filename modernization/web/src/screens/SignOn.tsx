import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Fields {
  userId: string;
}

/** COSGN00C: sign-on (transaction CC00). */
export function SignOn() {
  const s = useScreen<Fields>('COSGN00C');
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');

  useEffect(() => {
    if (!s.state) return;
    setUserId(s.state.fields?.userId ?? '');
    setPassword('');
  }, [s.state]);

  const press = useCallback(
    (key: string) => s.send(aid(key), { userId, password }),
    [s, userId, password],
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const invalid = (id: string) => s.state?.messageColor === 'RED' && s.state.focus === id;

  return (
    <ScreenFrame program="COSGN00C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="signon">
        <div className="signon-art" aria-hidden="true">
          <div>
            <h2>CardDemo</h2>
            <p>This is a Credit Card Demo Application for Mainframe Modernization</p>
          </div>
          <div className="cc">
            <div className="chip" />
            <div className="num">•••• •••• •••• 1234</div>
            <div className="meta">
              <span>CardDemo</span>
              <span>12/27</span>
            </div>
          </div>
          <div />
        </div>
        <form className="signon-form" noValidate aria-labelledby="t" onSubmit={submit}>
          <div>
            <h1 id="t">Sign on</h1>
            <p className="lede">Type your User ID and Password, then press ENTER.</p>
          </div>
          <Field id="userId" label="User ID" value={userId} onChange={setUserId} maxLength={8} hint="Up to 8 characters" upper autoComplete="username" invalid={invalid('userId')} />
          <Field id="password" label="Password" type="password" value={password} onChange={setPassword} maxLength={8} hint="Up to 8 characters" autoComplete="current-password" invalid={invalid('password')} />
          <button className="btn btn-primary" type="submit" disabled={s.busy}>
            Sign on <kbd>ENTER</kbd>
          </button>
        </form>
      </div>
    </ScreenFrame>
  );
}
