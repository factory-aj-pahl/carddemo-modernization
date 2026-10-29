import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { SCREENS, aid } from '../screens';
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
  keysEditable: boolean;
  detailsEditable: boolean;
  confirmKeys: boolean;
  state: string;
}

type Values = Pick<Fields, 'accountId' | 'cardNum' | 'name' | 'status' | 'expMonth' | 'expYear'>;

const EMPTY: Values = { accountId: '', cardNum: '', name: '', status: '', expMonth: '', expYear: '' };
const CONFIRM_KEYS = [
  { key: 'F5', label: 'Save' },
  { key: 'F12', label: 'Cancel' },
];

/** COCRDUPC: fetch a card, edit name, status, and expiry, confirm with F5 (transaction CCUP). */
export function CardUpdate() {
  const s = useScreen<Fields>('COCRDUPC');
  const [v, setV] = useState<Values>(EMPTY);
  const f = s.state?.fields;
  const errors = f?.errors ?? [];

  useEffect(() => {
    if (!s.state?.fields) return;
    const { accountId, cardNum, name, status, expMonth, expYear } = s.state.fields;
    setV({ accountId, cardNum, name, status, expMonth, expYear });
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), v), [s, v]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const set = (k: keyof Values) => (value: string) => setV((old) => ({ ...old, [k]: value }));
  const keysLocked = !(f?.keysEditable ?? true);
  const detailsLocked = !(f?.detailsEditable ?? false);
  const keys = f?.confirmKeys ? [...SCREENS.COCRDUPC.keys, ...CONFIRM_KEYS] : SCREENS.COCRDUPC.keys;

  return (
    <ScreenFrame program="COCRDUPC" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} keys={keys} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Update Credit Card Details</h1>
            <p className="lede">Fetch a card, change its details, press ENTER to validate and F5 to save.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit} data-state={f?.state ?? ''}>
          <div className="search-bar">
            <Field id="accountId" label="Account Number" value={v.accountId} onChange={set('accountId')} maxLength={11} inputMode="numeric" width="230px" placeholder="11 digits" readOnly={keysLocked} invalid={errors.includes('accountId')} />
            <Field id="cardNum" label="Card Number" value={v.cardNum} onChange={set('cardNum')} maxLength={16} inputMode="numeric" width="290px" placeholder="16 digits" readOnly={keysLocked} invalid={errors.includes('cardNum')} />
          </div>
          <hr className="divider" />
          <div className="row">
            <Field id="name" label="Name on card" value={v.name} onChange={set('name')} maxLength={50} width="420px" readOnly={detailsLocked} invalid={errors.includes('name')} />
            <Field id="status" label="Card Active Y/N" value={v.status} onChange={set('status')} maxLength={1} width="auto" inputClass="sel" readOnly={detailsLocked} invalid={errors.includes('status')} />
            <fieldset className="parts">
              <legend>Expiry Date</legend>
              <div className="parts-row">
                <Field id="expMonth" label="month" value={v.expMonth} onChange={set('expMonth')} maxLength={2} inputMode="numeric" width="80px" placeholder="MM" readOnly={detailsLocked} labelPrefix="Expiry Date" invalid={errors.includes('expMonth')} />
                <span aria-hidden="true" className="parts-sep">
                  /
                </span>
                <Field id="expYear" label="year" value={v.expYear} onChange={set('expYear')} maxLength={4} inputMode="numeric" width="96px" placeholder="YYYY" readOnly={detailsLocked} labelPrefix="Expiry Date" invalid={errors.includes('expYear')} />
              </div>
            </fieldset>
          </div>
          <p className="info-line" style={{ margin: '22px 0 0' }} data-testid="info">
            {f?.info ?? ''}
          </p>
          <div style={{ display: 'flex', gap: 10, marginTop: 22 }}>
            {/* In the confirm state the step the key bar offers is F5 Save, so Save is the primary button. */}
            <button className={`btn${f?.confirmKeys ? '' : ' btn-primary'}`} type="submit" disabled={s.busy}>
              Process <kbd>ENTER</kbd>
            </button>
            {f?.confirmKeys ? (
              <>
                <button className="btn btn-primary" type="button" onClick={() => press('F5')} disabled={s.busy}>
                  Save <kbd>F5</kbd>
                </button>
                <button className="btn" type="button" onClick={() => press('F12')} disabled={s.busy}>
                  Cancel <kbd>F12</kbd>
                </button>
              </>
            ) : null}
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
