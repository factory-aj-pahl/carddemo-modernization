import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { fieldInError, useScreen } from '../useScreen';

interface Row {
  sel: string;
  userId: string;
  firstName: string;
  lastName: string;
  userType: string;
}

interface Fields {
  searchUserId: string;
  pageNum: string;
  rows: Row[];
}

const ROWS = 10;

/** COUSR00C: list users ten per page; U updates and D deletes the user on that row (transaction CU00). */
export function UserList() {
  const s = useScreen<Fields>('COUSR00C');
  const [search, setSearch] = useState('');
  const [sel, setSel] = useState<string[]>(Array(ROWS).fill(''));
  const f = s.state?.fields;
  const rows = f?.rows ?? [];

  useEffect(() => {
    if (!s.state) return;
    setSearch(s.state.fields?.searchUserId ?? '');
    setSel(Array.from({ length: ROWS }, (_, i) => s.state?.fields?.rows?.[i]?.sel ?? ''));
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { searchUserId: search, sel }), [s, search, sel]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const pick = (i: number, code: string) => {
    const codes = Array<string>(ROWS).fill('');
    codes[i] = code;
    s.send('ENTER', { searchUserId: search, sel: codes });
  };
  const setCode = (i: number, v: string) => setSel((old) => old.map((c, j) => (j === i ? v.toUpperCase() : c)));
  const page = f?.pageNum ?? '';

  return (
    <ScreenFrame program="COUSR00C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page wide">
        <form noValidate onSubmit={submit}>
          <div className="page-head">
            <div>
              <h1>List Users</h1>
              <p className="lede">Security users, 10 per page in User ID order.</p>
            </div>
            <div className="page-no" data-testid="page">
              Page <strong>{page}</strong>
            </div>
          </div>
          <div className="panel compact">
            <div className="search-bar">
              <Field id="searchUserId" label="Search User ID" value={search} onChange={setSearch} maxLength={8} width="230px" placeholder="Optional" upper invalid={fieldInError(s.state, 'searchUserId')} />
              <button className="btn" type="submit" disabled={s.busy}>
                Continue <kbd>ENTER</kbd>
              </button>
            </div>
          </div>
          <div className="list-wrap">
            <table className="list">
              <caption className="sr-only">Users, page {page}</caption>
              <thead>
                <tr>
                  <th scope="col" style={{ width: 90 }}>
                    Sel
                  </th>
                  <th scope="col">User ID</th>
                  <th scope="col">First Name</th>
                  <th scope="col">Last Name</th>
                  <th scope="col" style={{ width: 90 }}>
                    Type
                  </th>
                  <th scope="col" style={{ width: 200 }}>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {Array.from({ length: ROWS }, (_, i) => {
                  const r = rows[i];
                  if (!r || !r.userId) {
                    return (
                      <tr className="empty-row" key={i}>
                        <td />
                        <td />
                        <td />
                        <td />
                        <td />
                        <td />
                      </tr>
                    );
                  }
                  const id = `sel${i + 1}`;
                  return (
                    <tr key={i} data-testid="user-row">
                      <td>
                        <label className="sr-only" htmlFor={id}>
                          Select user {r.userId} (U update, D delete)
                        </label>
                        <input className="input sel" id={id} name={id} maxLength={1} autoComplete="off" value={sel[i]} onChange={(e) => setCode(i, e.target.value)} />
                      </td>
                      <td className="mono">{r.userId}</td>
                      <td>{r.firstName}</td>
                      <td>{r.lastName}</td>
                      <td className="mono">{r.userType}</td>
                      <td>
                        <div className="actions">
                          <button type="button" className="btn btn-link btn-sm" onClick={() => pick(i, 'U')} disabled={s.busy}>
                            Update<span className="sr-only"> user {r.userId}</span> <kbd>U</kbd>
                          </button>
                          <button type="button" className="btn btn-link btn-sm" onClick={() => pick(i, 'D')} disabled={s.busy}>
                            Delete<span className="sr-only"> user {r.userId}</span> <kbd>D</kbd>
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="list-foot">
              <span className="info-line">Type &apos;U&apos; to Update or &apos;D&apos; to Delete a User from the list</span>
              <span className="hint">F7 previous page · F8 next page</span>
            </div>
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
