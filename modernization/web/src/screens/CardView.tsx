import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Fields {
  accountId: string;
  cardNum: string;
  name: string;
  status: string;
  expMonth: string;
  expYear: string;
  info: string;
  errors: string[];
  keysProtected: boolean;
}

function Value({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={`${value ? '' : 'empty '}${mono ? 'mono' : ''}`}>{value || '—'}</dd>
    </div>
  );
}

/** COCRDSLC: view one credit card by account and card number (transaction CCDL). */
export function CardView() {
  const s = useScreen<Fields>('COCRDSLC');
  const [accountId, setAccountId] = useState('');
  const [cardNum, setCardNum] = useState('');
  const f = s.state?.fields;
  const errors = f?.errors ?? [];
  const locked = f?.keysProtected ?? false;

  useEffect(() => {
    if (!s.state) return;
    setAccountId(s.state.fields?.accountId ?? '');
    setCardNum(s.state.fields?.cardNum ?? '');
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { accountId, cardNum }), [s, accountId, cardNum]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const expiry = f?.expMonth || f?.expYear ? `${f?.expMonth ?? ''} / ${f?.expYear ?? ''}` : '';

  return (
    <ScreenFrame program="COCRDSLC" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>View Credit Card Detail</h1>
            <p className="lede">Read-only view of one card.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit}>
          <div className="search-bar">
            <Field id="accountId" label="Account Number" value={accountId} onChange={setAccountId} maxLength={11} inputMode="numeric" width="230px" placeholder="11 digits" readOnly={locked} invalid={errors.includes('accountId')} />
            <Field id="cardNum" label="Card Number" value={cardNum} onChange={setCardNum} maxLength={16} inputMode="numeric" width="290px" placeholder="16 digits" readOnly={locked} invalid={errors.includes('cardNum')} />
            <button className="btn" type="submit" disabled={s.busy}>
              Search Cards <kbd>ENTER</kbd>
            </button>
          </div>
          <hr className="divider" />
          <dl className="kv cols-3" data-testid="card-details">
            <Value label="Name on card" value={f?.name ?? ''} />
            <div>
              <dt>Card Active Y/N</dt>
              <dd className={f?.status ? '' : 'empty'}>{f?.status ? <span className={`pill${f.status === 'Y' ? ' yes' : ''}`}>{f.status}</span> : '—'}</dd>
            </div>
            <Value label="Expiry Date" value={expiry} mono />
          </dl>
          <p className="info-line" style={{ margin: '22px 0 0', whiteSpace: 'pre' }} data-testid="info">
            {f?.info ?? ''}
          </p>
        </form>
      </div>
    </ScreenFrame>
  );
}
