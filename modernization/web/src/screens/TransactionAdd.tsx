import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { keepTyped } from '../format';
import { aid } from '../screens';
import { fieldInError, useScreen } from '../useScreen';

interface Fields {
  accountId: string;
  cardNum: string;
  typeCd: string;
  catCd: string;
  source: string;
  description: string;
  amount: string;
  origDate: string;
  procDate: string;
  merchantId: string;
  merchantName: string;
  merchantCity: string;
  merchantZip: string;
  confirm: string;
}

type Name = keyof Fields;

const EMPTY: Fields = {
  accountId: '',
  cardNum: '',
  typeCd: '',
  catCd: '',
  source: '',
  description: '',
  amount: '',
  origDate: '',
  procDate: '',
  merchantId: '',
  merchantName: '',
  merchantCity: '',
  merchantZip: '',
  confirm: '',
};

interface Spec {
  name: Name;
  label: string;
  max: number;
  span?: number;
  numeric?: boolean;
  placeholder?: string;
  /** BMS format literal shown next to the field. */
  hint?: string;
}

const DATA: Spec[] = [
  { name: 'typeCd', label: 'Type CD', max: 2, numeric: true, placeholder: '2 digits' },
  { name: 'catCd', label: 'Category CD', max: 4, numeric: true, placeholder: '4 digits' },
  { name: 'source', label: 'Source', max: 10, span: 2 },
  { name: 'description', label: 'Description', max: 60, span: 4 },
  { name: 'amount', label: 'Amount', max: 12, hint: '(-99999999.99)', span: 2 },
  { name: 'origDate', label: 'Orig Date', max: 10, hint: '(YYYY-MM-DD)' },
  { name: 'procDate', label: 'Proc Date', max: 10, hint: '(YYYY-MM-DD)' },
  { name: 'merchantId', label: 'Merchant ID', max: 9, numeric: true, placeholder: '9 digits' },
  { name: 'merchantName', label: 'Merchant Name', max: 30, span: 3 },
  { name: 'merchantCity', label: 'Merchant City', max: 25, span: 2 },
  { name: 'merchantZip', label: 'Merchant Zip', max: 10 },
];

const grid = { display: 'grid', gridTemplateColumns: 'repeat(4,minmax(0,1fr))', gap: '16px 20px' } as const;

/** COTRN02C: add a transaction (transaction CT02). */
export function TransactionAdd() {
  const s = useScreen<Fields>('COTRN02C');
  const [form, setForm] = useState<Fields>(EMPTY);

  useEffect(() => {
    if (!s.state) return;
    const from = { ...EMPTY, ...(s.state.fields ?? {}) };
    // The server echoes the card masked; keep the full number the user typed so the next turn still matches.
    setForm((prev) => ({ ...from, cardNum: keepTyped(prev.cardNum, from.cardNum) }));
  }, [s.state]);

  const set = (name: Name) => (v: string) => setForm((prev) => ({ ...prev, [name]: v }));
  const press = useCallback((key: string) => s.send(aid(key), { ...form }), [s, form]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const invalid = (id: string) => fieldInError(s.state, id);

  return (
    <ScreenFrame program="COTRN02C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Add Transaction</h1>
            <p className="lede">Record a transaction against one of your accounts or cards.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit}>
          <div className="search-bar">
            <Field id="accountId" label="Enter Acct #" value={form.accountId} onChange={set('accountId')} maxLength={11} inputMode="numeric" width="230px" placeholder="11 digits" invalid={invalid('accountId')} />
            <span className="hint" style={{ alignSelf: 'center', paddingTop: 20 }}>
              (or)
            </span>
            <Field id="cardNum" label="Card #" value={form.cardNum} onChange={set('cardNum')} maxLength={16} inputMode="numeric" width="290px" placeholder="16 digits" invalid={invalid('cardNum')} />
          </div>
          <hr className="divider" />
          <div style={grid}>
            {DATA.map((d) => (
              <div key={d.name} style={d.span ? { gridColumn: `span ${d.span}` } : undefined}>
                <Field
                  id={d.name}
                  label={d.label}
                  value={form[d.name]}
                  onChange={set(d.name)}
                  maxLength={d.max}
                  inputMode={d.numeric ? 'numeric' : undefined}
                  placeholder={d.placeholder}
                  hint={d.hint}
                  invalid={invalid(d.name)}
                />
              </div>
            ))}
          </div>
          <hr className="divider" />
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <Field id="confirm" label="You are about to add this transaction. Please confirm :" value={form.confirm} onChange={set('confirm')} maxLength={1} width="auto" hint="(Y/N)" upper inputClass="sel" invalid={invalid('confirm')} />
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 22 }}>
            <button className="btn btn-primary" type="submit" disabled={s.busy}>
              Continue <kbd>ENTER</kbd>
            </button>
            <button className="btn" type="button" onClick={() => press('F4')} disabled={s.busy}>
              Clear <kbd>F4</kbd>
            </button>
            <button className="btn" type="button" onClick={() => press('F5')} disabled={s.busy}>
              Copy Last Tran. <kbd>F5</kbd>
            </button>
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
