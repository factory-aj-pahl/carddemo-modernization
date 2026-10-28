import { useCallback } from 'react';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Fields {
  cardNum: string;
  authDate: string;
  authTime: string;
  authResp: string;
  respReason: string;
  authCode: string;
  amount: string;
  posEntryMode: string;
  source: string;
  mccCode: string;
  cardExpiry: string;
  authType: string;
  transactionId: string;
  matchStatus: string;
  fraudStatus: string;
  merchantName: string;
  merchantId: string;
  merchantCity: string;
  merchantState: string;
  merchantZip: string;
}

function Value({ label, value, id, testId }: { label: string; value: string; id?: string; testId?: string }) {
  const v = value.trim();
  return (
    <div>
      <dt>{label}</dt>
      <dd id={id} tabIndex={id ? -1 : undefined} className={`mono${v ? '' : ' empty'}`} data-testid={testId}>
        {v || '—'}
      </dd>
    </div>
  );
}

/** Approved shows green, declined red (AUTHRSP color in COPAUS1C). */
function Response({ value }: { value: string }) {
  if (!value) return <dd className="empty">—</dd>;
  const declined = value === 'D';
  return (
    <dd data-testid="auth-resp">
      <span
        className={`pill${declined ? '' : ' yes'}`}
        style={declined ? { background: 'var(--error-soft)', borderColor: 'var(--error-border)', color: 'var(--error)' } : undefined}
      >
        {value}
        <span className="sr-only">{declined ? ' (declined)' : ' (approved)'}</span>
      </span>
    </dd>
  );
}

/** COPAUS1C: one pending authorization; F5 marks or removes fraud, F8 shows the next older one (transaction CPVD). */
export function PendingAuthDetail() {
  const s = useScreen<Fields>('COPAUS1C');
  const f = s.state?.fields;
  const press = useCallback((key: string) => s.send(aid(key), {}), [s]);
  const v = (k: keyof Fields) => f?.[k] ?? '';

  return (
    <ScreenFrame program="COPAUS1C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>View Authorization Details</h1>
            <p className="lede">One pending authorization. The card number is masked.</p>
          </div>
          <button type="button" className="btn" onClick={() => press('ENTER')} disabled={s.busy}>
            Refresh <kbd>ENTER</kbd>
          </button>
        </div>
        <div className="panel" data-testid="auth-details">
          <dl className="kv cols-4">
            <Value label="Card #" value={v('cardNum')} id="cardNum" />
            <Value label="Auth Date" value={v('authDate')} />
            <Value label="Auth Time" value={v('authTime')} />
            <div>
              <dt>Auth Resp</dt>
              <Response value={v('authResp')} />
            </div>
            <Value label="Resp Reason" value={v('respReason')} testId="resp-reason" />
            <Value label="Auth Code" value={v('authCode')} />
            <Value label="Amount" value={v('amount')} testId="amount" />
            <Value label="POS Entry Mode" value={v('posEntryMode')} />
            <Value label="Source" value={v('source')} />
            <Value label="MCC Code" value={v('mccCode')} />
            <Value label="Card Exp. Date" value={v('cardExpiry')} />
            <Value label="Auth Type" value={v('authType')} />
            <Value label="Tran Id" value={v('transactionId')} testId="transaction-id" />
            <Value label="Match Status" value={v('matchStatus')} />
            <Value label="Fraud Status" value={v('fraudStatus')} testId="fraud-status" />
          </dl>
          <hr className="divider" />
          <p className="section-label">Merchant Details</p>
          <dl className="kv cols-4">
            <div className="span-2">
              <dt>Name</dt>
              <dd className={v('merchantName').trim() ? '' : 'empty'} data-testid="merchant-name">
                {v('merchantName').trim() || '—'}
              </dd>
            </div>
            <Value label="Merchant ID" value={v('merchantId')} />
            <Value label="City" value={v('merchantCity')} />
            <Value label="State" value={v('merchantState')} />
            <Value label="Zip" value={v('merchantZip')} />
          </dl>
        </div>
      </div>
    </ScreenFrame>
  );
}
