import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { fieldInError, useScreen } from '../useScreen';

interface Row {
  sel: string;
  transactionId: string;
  date: string;
  time: string;
  type: string;
  approval: string;
  status: string;
  amount: string;
  selectable: boolean;
}

interface Fields {
  accountId: string;
  name: string;
  customerId: string;
  address1: string;
  address2: string;
  accountStatus: string;
  phone: string;
  approvedCount: string;
  declinedCount: string;
  creditLimit: string;
  cashLimit: string;
  approvedAmount: string;
  creditBalance: string;
  cashBalance: string;
  declinedAmount: string;
  pageNum: string;
  rows: Row[];
}

const ROWS = 5;

function Value({ label, value, testId }: { label: string; value: string; testId?: string }) {
  const v = value.trim();
  return (
    <div>
      <dt>{label}</dt>
      <dd className={`mono${v ? '' : ' empty'}`} data-testid={testId}>
        {v || '—'}
      </dd>
    </div>
  );
}

/** COPAUS0C: an account's customer, limits, and pending authorizations five per page, newest first (transaction CPVS). */
export function PendingAuthList() {
  const s = useScreen<Fields>('COPAUS0C');
  const [accountId, setAccountId] = useState('');
  const [sel, setSel] = useState<string[]>(Array(ROWS).fill(''));
  const f = s.state?.fields;
  const rows = f?.rows ?? [];
  const red = fieldInError(s.state, 'accountId');

  useEffect(() => {
    if (!s.state) return;
    setAccountId(s.state.fields?.accountId ?? '');
    setSel(Array.from({ length: ROWS }, (_, i) => s.state?.fields?.rows?.[i]?.sel ?? ''));
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { accountId, sel }), [s, accountId, sel]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const pick = (i: number) => {
    const codes = Array<string>(ROWS).fill('');
    codes[i] = 'S';
    s.send('ENTER', { accountId, sel: codes });
  };
  const setCode = (i: number, v: string) => setSel((old) => old.map((c, j) => (j === i ? v : c)));

  return (
    <ScreenFrame program="COPAUS0C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page wide">
        <form noValidate onSubmit={submit}>
          <div className="page-head">
            <div>
              <h1>View Authorizations</h1>
              <p className="lede">Pending authorizations of one account, newest first.</p>
            </div>
          </div>
          <div className="panel compact">
            <div className="search-bar">
              <Field id="accountId" label="Search Acct Id" value={accountId} onChange={setAccountId} maxLength={11} inputMode="numeric" width="230px" placeholder="11 digits" invalid={red} />
              <button className="btn" type="submit" disabled={s.busy}>
                Search <kbd>ENTER</kbd>
              </button>
            </div>
            <hr className="divider" />
            <dl className="kv cols-4" data-testid="account-summary">
              <Value label="Name" value={f?.name ?? ''} testId="name" />
              <Value label="Customer Id" value={f?.customerId ?? ''} />
              <Value label="Acct Status" value={f?.accountStatus ?? ''} />
              <Value label="PH" value={f?.phone ?? ''} />
              <div className="span-2">
                <dt>Address</dt>
                <dd className={f?.address1?.trim() ? '' : 'empty'}>
                  {f?.address1?.trim() ? (
                    <>
                      {f.address1.trim()}
                      <br />
                      {f.address2.trim()}
                    </>
                  ) : (
                    '—'
                  )}
                </dd>
              </div>
              <Value label="Credit Lim" value={f?.creditLimit ?? ''} />
              <Value label="Cash Lim" value={f?.cashLimit ?? ''} />
              <Value label="Approval #" value={f?.approvedCount ?? ''} testId="approved-count" />
              <Value label="Appr Amt" value={f?.approvedAmount ?? ''} testId="approved-amount" />
              <Value label="Credit Bal" value={f?.creditBalance ?? ''} />
              <Value label="Cash Bal" value={f?.cashBalance ?? ''} />
              <Value label="Decline #" value={f?.declinedCount ?? ''} testId="declined-count" />
              <Value label="Decl Amt" value={f?.declinedAmount ?? ''} />
            </dl>
          </div>
          <div className="list-wrap">
            <table className="list">
              <caption className="sr-only">Pending authorizations</caption>
              <thead>
                <tr>
                  <th scope="col" style={{ width: 90 }}>
                    Select
                  </th>
                  <th scope="col">Transaction ID</th>
                  <th scope="col">Date</th>
                  <th scope="col">Time</th>
                  <th scope="col">Type</th>
                  <th scope="col">A/D</th>
                  <th scope="col">STS</th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Amount
                  </th>
                  <th scope="col" style={{ width: 110 }}>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {Array.from({ length: ROWS }, (_, i) => {
                  const r = rows[i];
                  if (!r || !r.selectable) {
                    return (
                      <tr className="empty-row" key={i}>
                        <td colSpan={9} />
                      </tr>
                    );
                  }
                  const id = `sel${i + 1}`;
                  const txn = r.transactionId.trim();
                  return (
                    <tr key={i} data-testid="auth-row">
                      <td>
                        <label className="sr-only" htmlFor={id}>
                          Select authorization {txn} (S to view)
                        </label>
                        <input className="input sel" id={id} name={id} maxLength={1} autoComplete="off" value={sel[i]} onChange={(e) => setCode(i, e.target.value)} />
                      </td>
                      <td className="mono">{txn}</td>
                      <td className="mono">{r.date}</td>
                      <td className="mono">{r.time}</td>
                      <td className="mono">{r.type.trim()}</td>
                      <td>
                        <span className={`pill${r.approval === 'A' ? ' yes' : ''}`}>{r.approval}</span>
                      </td>
                      <td className="mono">{r.status.trim()}</td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        {r.amount.trim()}
                      </td>
                      <td>
                        <div className="actions">
                          <button type="button" className="btn btn-link btn-sm" onClick={() => pick(i)} disabled={s.busy}>
                            View<span className="sr-only"> authorization {txn}</span> <kbd>S</kbd>
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="list-foot">
              <span className="info-line">Type &apos;S&apos; to View Authorization details from the list</span>
              <span className="hint">F7 previous page · F8 next page</span>
            </div>
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
