import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Fields {
  firstName: string;
  lastName: string;
  userId: string;
  userType: string;
  accounts: string;
}

const EMPTY: Fields = { firstName: '', lastName: '', userId: '', userType: '', accounts: '' };

/** COUSR01C: add a user (transaction CU01). The password is never sent back by the server. */
export function UserAdd() {
  const s = useScreen<Fields>('COUSR01C');
  const [v, setV] = useState<Fields>(EMPTY);
  const [password, setPassword] = useState('');

  useEffect(() => {
    if (!s.state) return;
    const f = { ...EMPTY, ...s.state.fields };
    setV(f);
    // A cleared screen (first entry, F4, or a completed add) also clears the password.
    if (!f.userId && !f.firstName && !f.lastName) setPassword('');
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { ...v, password }), [s, v, password]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const set = (k: keyof Fields) => (value: string) => setV((old) => ({ ...old, [k]: value }));
  const invalid = (id: string) => s.state?.messageColor === 'RED' && s.state.focus === id;

  return (
    <ScreenFrame program="COUSR01C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Add User</h1>
            <p className="lede">Enter the new user's details and press ENTER.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit}>
          <div className="row">
            <Field id="firstName" label="First Name" value={v.firstName} onChange={set('firstName')} maxLength={20} width="300px" invalid={invalid('firstName')} />
            <Field id="lastName" label="Last Name" value={v.lastName} onChange={set('lastName')} maxLength={20} width="300px" invalid={invalid('lastName')} />
          </div>
          <div className="row" style={{ marginTop: 16 }}>
            <Field id="userId" label="User ID" value={v.userId} onChange={set('userId')} maxLength={8} width="200px" hint="(8 Char)" upper invalid={invalid('userId')} />
            <Field id="password" label="Password" type="password" value={password} onChange={setPassword} maxLength={8} width="200px" hint="(8 Char)" autoComplete="new-password" invalid={invalid('password')} />
            <Field id="userType" label="User Type" value={v.userType} onChange={set('userType')} maxLength={1} width="auto" hint="(A=Admin, U=User)" upper inputClass="sel" invalid={invalid('userType')} />
          </div>
          <div className="row" style={{ marginTop: 16 }}>
            <Field id="accounts" label="Linked Accounts" value={v.accounts} onChange={set('accounts')} width="620px" hint="11-digit account numbers separated by commas (regular users only)" invalid={invalid('accounts')} />
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 22 }}>
            <button className="btn btn-primary" type="submit" disabled={s.busy}>
              Add User <kbd>ENTER</kbd>
            </button>
            <button className="btn" type="button" onClick={() => press('F4')} disabled={s.busy}>
              Clear <kbd>F4</kbd>
            </button>
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
