import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Row {
  sel: string;
  accountId: string;
  cardNum: string;
  status: string;
  selectable: boolean;
  error: boolean;
}

interface Fields {
  accountFilter: string;
  cardFilter: string;
  pageNum: string;
  rows: Row[];
  info: string;
  errors: string[];
}

const ROWS = 7;

/** COCRDLIC: credit cards in scope, seven per page; S views and U updates a card (transaction CCLI). */
export function CardList() {
  const s = useScreen<Fields>('COCRDLIC');
  const [accountFilter, setAccountFilter] = useState('');
  const [cardFilter, setCardFilter] = useState('');
  const [sel, setSel] = useState<string[]>(Array(ROWS).fill(''));
  const f = s.state?.fields;
  const rows = f?.rows ?? [];
  const errors = f?.errors ?? [];

  useEffect(() => {
    if (!s.state) return;
    setAccountFilter(s.state.fields?.accountFilter ?? '');
    setCardFilter(s.state.fields?.cardFilter ?? '');
    setSel(Array.from({ length: ROWS }, (_, i) => s.state?.fields?.rows?.[i]?.sel ?? ''));
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { accountFilter, cardFilter, sel }), [s, accountFilter, cardFilter, sel]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const pick = (i: number, code: string) => {
    const codes = Array<string>(ROWS).fill('');
    codes[i] = code;
    s.send('ENTER', { accountFilter, cardFilter, sel: codes });
  };
  const setCode = (i: number, v: string) => setSel((old) => old.map((c, j) => (j === i ? v.toUpperCase() : c)));

  return (
    <ScreenFrame program="COCRDLIC" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page wide">
        <form noValidate onSubmit={submit}>
          <div className="page-head">
            <div>
              <h1>List Credit Cards</h1>
              <p className="lede">Cards on the accounts you may see. Filter by account or card number.</p>
            </div>
            <div className="page-no" data-testid="page">
              Page <strong>{f?.pageNum ?? ''}</strong>
            </div>
          </div>
          <div className="panel compact">
            <div className="search-bar">
              <Field id="accountFilter" label="Account Number" value={accountFilter} onChange={setAccountFilter} maxLength={11} inputMode="numeric" width="230px" placeholder="Optional, 11 digits" invalid={errors.includes('accountFilter')} />
              <Field id="cardFilter" label="Credit Card Number" value={cardFilter} onChange={setCardFilter} maxLength={16} inputMode="numeric" width="290px" placeholder="Optional, 16 digits" invalid={errors.includes('cardFilter')} />
              <button className="btn" type="submit" disabled={s.busy}>
                Apply filter <kbd>ENTER</kbd>
              </button>
            </div>
          </div>
          <div className="list-wrap">
            <table className="list">
              <caption className="sr-only">Credit cards, page {f?.pageNum ?? ''}</caption>
              <thead>
                <tr>
                  <th scope="col" style={{ width: 90 }}>
                    Select
                  </th>
                  <th scope="col">Account Number</th>
                  <th scope="col">Card Number</th>
                  <th scope="col" style={{ width: 100 }}>
                    Active
                  </th>
                  <th scope="col" style={{ width: 200 }}>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {Array.from({ length: ROWS }, (_, i) => {
                  const r = rows[i];
                  if (!r || !r.cardNum) {
                    return (
                      <tr className="empty-row" key={i}>
                        <td />
                        <td />
                        <td />
                        <td />
                        <td />
                      </tr>
                    );
                  }
                  const id = `sel${i + 1}`;
                  const last4 = r.cardNum.slice(-4);
                  return (
                    <tr key={i} className={r.error ? 'err' : undefined} data-testid="card-row">
                      <td>
                        <label className="sr-only" htmlFor={id}>
                          Select card ending {last4} (S detail, U update)
                        </label>
                        <input
                          className="input sel"
                          id={id}
                          name={id}
                          maxLength={1}
                          autoComplete="off"
                          value={sel[i]}
                          disabled={!r.selectable}
                          aria-invalid={r.error ? true : undefined}
                          onChange={(e) => setCode(i, e.target.value)}
                        />
                      </td>
                      <td className="mono">{r.accountId}</td>
                      <td className="mono">{r.cardNum}</td>
                      <td>
                        <span className={`pill${r.status === 'Y' ? ' yes' : ''}`}>{r.status}</span>
                      </td>
                      <td>
                        <div className="actions">
                          <button type="button" className="btn btn-link btn-sm" onClick={() => pick(i, 'S')} disabled={s.busy || !r.selectable}>
                            View<span className="sr-only"> card ending {last4}</span> <kbd>S</kbd>
                          </button>
                          <button type="button" className="btn btn-link btn-sm" onClick={() => pick(i, 'U')} disabled={s.busy || !r.selectable}>
                            Update<span className="sr-only"> card ending {last4}</span> <kbd>U</kbd>
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="list-foot">
              <span className="info-line" data-testid="info">
                {f?.info ?? ''}
              </span>
              <span className="hint">F7 previous page · F8 next page</span>
            </div>
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
