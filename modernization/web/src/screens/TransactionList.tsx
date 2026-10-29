import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { fieldInError, useScreen } from '../useScreen';

interface Row {
  tranId: string;
  date: string;
  description: string;
  amount: string;
}

interface Fields {
  searchTranId: string;
  page: string;
  rows: Row[];
}

const PAGE_SIZE = 10;

// With a blank search, "Transaction ID NOT found..." means the user has no transactions in scope (the empty
// page, Q-COTRN00C-02): it is about the list, not the field. With a typed ID it is about that ID.
const NOT_ABOUT_BLANK_SEARCH = [/^Transaction ID NOT found/];
const blank = () => Array.from({ length: PAGE_SIZE }, () => '');

/** COTRN00C: paged list of transactions (transaction CT00). */
export function TransactionList() {
  const s = useScreen<Fields>('COTRN00C');
  const [search, setSearch] = useState('');
  const [sel, setSel] = useState<string[]>(blank);
  const f = s.state?.fields;
  const rows = f?.rows ?? [];
  // The server always sends the search field back blank, so the screen remembers what the turn was sent with.
  const sentSearch = useRef('');

  useEffect(() => {
    if (!s.state) return;
    setSearch(s.state.fields?.searchTranId ?? '');
    setSel(blank());
  }, [s.state]);

  const send = useCallback(
    (key: string, selections: string[]) => {
      if (s.busy) return;
      sentSearch.current = search;
      s.send(aid(key), { searchTranId: search, selections });
    },
    [s, search],
  );
  const press = useCallback((key: string) => send(key, sel), [send, sel]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const view = (i: number) => {
    const next = blank();
    next[i] = 'S';
    send('ENTER', next);
  };
  const invalid = fieldInError(s.state, 'searchTranId', sentSearch.current.trim() ? [] : NOT_ABOUT_BLANK_SEARCH);
  const pageNo = f?.page ?? '';

  return (
    <ScreenFrame program="COTRN00C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <form id="form" noValidate onSubmit={submit}>
          <div className="page-head">
            <div>
              <h1>List Transactions</h1>
              <p className="lede">Transactions of your accounts, 10 per page in transaction ID order.</p>
            </div>
            <div className="page-no" data-testid="page" data-raw={f?.page ?? ''}>
              Page <strong>{pageNo}</strong>
            </div>
          </div>
          <div className="panel compact">
            <div className="search-bar">
              <Field id="searchTranId" label="Search Tran ID" value={search} onChange={setSearch} maxLength={16} inputMode="numeric" width="290px" placeholder="16 digits" invalid={invalid} />
              <button className="btn" type="submit" disabled={s.busy}>
                Search <kbd>ENTER</kbd>
              </button>
            </div>
          </div>
          <div className="list-wrap">
            <table className="list">
              <caption className="sr-only">Transactions, page {pageNo}</caption>
              <thead>
                <tr>
                  <th scope="col" style={{ width: 80 }}>
                    Sel
                  </th>
                  <th scope="col">Transaction ID</th>
                  <th scope="col">Date</th>
                  <th scope="col">Description</th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Amount
                  </th>
                  <th scope="col" style={{ width: 100 }}>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {Array.from({ length: PAGE_SIZE }, (_, i) => {
                  const r = rows[i];
                  if (!r)
                    return (
                      <tr key={i} className="empty-row">
                        <td />
                        <td />
                        <td />
                        <td />
                        <td />
                        <td />
                      </tr>
                    );
                  return (
                    <tr key={r.tranId} data-testid="row">
                      <td>
                        <label className="sr-only" htmlFor={`sel${i}`}>
                          Select transaction {r.tranId}
                        </label>
                        <input
                          className="input sel"
                          id={`sel${i}`}
                          name={`sel${i}`}
                          maxLength={1}
                          autoComplete="off"
                          value={sel[i]}
                          onChange={(e) => {
                            const next = [...sel];
                            next[i] = e.target.value.toUpperCase();
                            setSel(next);
                          }}
                        />
                      </td>
                      <td className="mono">{r.tranId}</td>
                      <td className="mono">{r.date}</td>
                      <td>{r.description}</td>
                      <td className="mono" style={{ textAlign: 'right' }} data-raw={r.amount}>
                        {r.amount}
                      </td>
                      <td>
                        <div className="actions">
                          <button type="button" className="btn btn-link btn-sm" onClick={() => view(i)} disabled={s.busy}>
                            View <kbd>S</kbd>
                            <span className="sr-only"> transaction {r.tranId}</span>
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="list-foot">
              <span className="info-line">Type &apos;S&apos; to View Transaction details from the list</span>
              <span className="hint">{rows.length ? `${rows.length} on this page` : 'No transactions'}</span>
            </div>
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
