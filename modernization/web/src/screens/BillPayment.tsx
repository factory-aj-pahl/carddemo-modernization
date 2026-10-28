import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Receipt {
  tranId: string;
  accountId: string;
  amountPaid: string;
  newBalance: string;
}

interface Fields {
  accountId: string;
  currentBalance: string;
  confirm: string;
  receipt?: Receipt;
}

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

function money(text: string): string {
  const n = Number(text.trim());
  return Number.isFinite(n) ? usd.format(n) : text;
}

/** COBIL00C: pay the full current balance of an account (transaction CB00). */
export function BillPayment() {
  const s = useScreen<Fields>('COBIL00C');
  const [accountId, setAccountId] = useState('');
  const [confirm, setConfirm] = useState('');
  const f = s.state?.fields;

  useEffect(() => {
    if (!s.state) return;
    setAccountId(s.state.fields?.accountId ?? '');
    setConfirm(s.state.fields?.confirm ?? '');
  }, [s.state]);

  const press = useCallback(
    (key: string) => s.send(aid(key), { accountId, confirm, shownBalance: f?.currentBalance ?? '' }),
    [s, accountId, confirm, f],
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const invalid = (id: string) => s.state?.messageColor === 'RED' && s.state.focus === id;
  const balance = f?.currentBalance ?? '';
  const receipt = f?.receipt;

  return (
    <ScreenFrame program="COBIL00C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Bill Payment</h1>
            <p className="lede">Pay the full current balance of an account.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit}>
          <div className="search-bar">
            <Field id="accountId" label="Enter Acct ID" value={accountId} onChange={setAccountId} maxLength={11} inputMode="numeric" width="240px" placeholder="11 digits" invalid={invalid('accountId')} />
          </div>
          <hr className="divider" />
          <p className="section-label" id="bal-l">
            Your current balance is
          </p>
          <div className="balance" aria-labelledby="bal-l">
            <span className={`amt${balance ? '' : ' none'}`} data-testid="balance" data-raw={balance}>
              {balance ? money(balance) : '—'}
            </span>
            <span className="hint">{balance ? 'Shown when you pressed ENTER' : 'Enter an account and press ENTER to see the balance'}</span>
          </div>
          <hr className="divider" />
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <Field id="confirm" label="Do you want to pay your balance now. Please confirm:" value={confirm} onChange={setConfirm} maxLength={1} width="auto" hint="Y = pay now, N = cancel" upper inputClass="sel" invalid={invalid('confirm')} />
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 22 }}>
            <button className="btn btn-primary" type="submit" disabled={s.busy}>
              Continue <kbd>ENTER</kbd>
            </button>
            <button className="btn" type="button" onClick={() => press('F4')} disabled={s.busy}>
              Clear <kbd>F4</kbd>
            </button>
          </div>
        </form>
        {receipt ? (
          <div className="receipt" role="group" aria-label="Payment receipt" data-testid="receipt">
            <span className="tick" aria-hidden="true">
              ✓
            </span>
            <dl>
              <div>
                <dt>Transaction ID</dt>
                <dd>{receipt.tranId}</dd>
              </div>
              <div>
                <dt>Account</dt>
                <dd>{receipt.accountId}</dd>
              </div>
              <div>
                <dt>Amount paid</dt>
                <dd>{money(receipt.amountPaid)}</dd>
              </div>
              <div>
                <dt>New balance</dt>
                <dd>{money(receipt.newBalance)}</dd>
              </div>
            </dl>
          </div>
        ) : null}
      </div>
    </ScreenFrame>
  );
}
