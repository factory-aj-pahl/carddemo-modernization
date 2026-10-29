import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { fieldInError, useScreen } from '../useScreen';

interface Fields {
  userId: string;
  firstName: string;
  lastName: string;
  userType: string;
}

function Value({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={`${value ? '' : 'empty '}${mono ? 'mono' : ''}`}>{value || '—'}</dd>
    </div>
  );
}

/** COUSR03C: fetch a user with ENTER and delete it with F5 (transaction CU03). */
export function UserDelete() {
  const s = useScreen<Fields>('COUSR03C');
  const [userId, setUserId] = useState('');
  const f = s.state?.fields;

  useEffect(() => {
    if (s.state) setUserId(s.state.fields?.userId ?? '');
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { userId }), [s, userId]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };

  return (
    <ScreenFrame program="COUSR03C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Delete User</h1>
            <p className="lede">Enter a User ID and press ENTER to fetch it; F5 deletes it.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit}>
          <div className="search-bar">
            <Field id="userId" label="Enter User ID" value={userId} onChange={setUserId} maxLength={8} width="230px" upper invalid={fieldInError(s.state, 'userId')} />
            <button className="btn" type="submit" disabled={s.busy}>
              Fetch <kbd>ENTER</kbd>
            </button>
          </div>
          <hr className="divider" />
          <dl className="kv cols-3" data-testid="user-details">
            <Value label="First Name" value={f?.firstName ?? ''} />
            <Value label="Last Name" value={f?.lastName ?? ''} />
            <Value label="User Type (A=Admin, U=User)" value={f?.userType ?? ''} mono />
          </dl>
          <div style={{ display: 'flex', gap: 10, marginTop: 22 }}>
            <button className="btn btn-primary" type="button" onClick={() => press('F5')} disabled={s.busy}>
              Delete <kbd>F5</kbd>
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
