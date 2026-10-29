import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { AreaField, Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { fieldInError, useScreen } from '../useScreen';

interface Fields {
  userId: string;
  firstName: string;
  lastName: string;
  userType: string;
  accounts: string;
}

const EMPTY: Fields = { userId: '', firstName: '', lastName: '', userType: '', accounts: '' };

/** COUSR02C: fetch a user with ENTER, change the details, save with F5 or F3 (transaction CU02). */
export function UserUpdate() {
  const s = useScreen<Fields>('COUSR02C');
  const [v, setV] = useState<Fields>(EMPTY);
  const [password, setPassword] = useState('');

  useEffect(() => {
    if (!s.state) return;
    setV({ ...EMPTY, ...s.state.fields });
    if (s.state.messageColor !== 'RED') setPassword('');
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { ...v, password }), [s, v, password]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const set = (k: keyof Fields) => (value: string) => setV((old) => ({ ...old, [k]: value }));
  const invalid = (id: string) => fieldInError(s.state, id);

  return (
    <ScreenFrame program="COUSR02C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Update User</h1>
            <p className="lede">Enter a User ID and press ENTER to fetch it; F5 saves the changes.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit}>
          <div className="search-bar">
            <Field id="userId" label="Enter User ID" value={v.userId} onChange={set('userId')} maxLength={8} width="230px" upper invalid={invalid('userId')} />
            <button className="btn" type="submit" disabled={s.busy}>
              Fetch <kbd>ENTER</kbd>
            </button>
          </div>
          <hr className="divider" />
          <div className="row">
            <Field id="firstName" label="First Name" value={v.firstName} onChange={set('firstName')} maxLength={20} width="300px" invalid={invalid('firstName')} />
            <Field id="lastName" label="Last Name" value={v.lastName} onChange={set('lastName')} maxLength={20} width="300px" invalid={invalid('lastName')} />
          </div>
          <div className="row" style={{ marginTop: 16 }}>
            <Field id="password" label="Password" type="password" value={password} onChange={setPassword} maxLength={8} width="200px" hint="(8 Char) blank keeps the current password" autoComplete="new-password" invalid={invalid('password')} />
            <Field id="userType" label="User Type" value={v.userType} onChange={set('userType')} maxLength={1} width="auto" hint="(A=Admin, U=User)" upper inputClass="sel" invalid={invalid('userType')} />
          </div>
          <div className="row" style={{ marginTop: 16 }}>
            <AreaField id="accounts" label="Linked Accounts" value={v.accounts} onChange={set('accounts')} width="100%" hint="11-digit account numbers separated by commas (regular users only)" invalid={invalid('accounts')} />
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 22 }}>
            <button className="btn btn-primary" type="button" onClick={() => press('F5')} disabled={s.busy}>
              Save <kbd>F5</kbd>
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
