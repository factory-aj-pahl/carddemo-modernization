import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { money } from '../format';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Fields {
  tranIdIn: string;
  tranId: string;
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
}

function Kv({ label, value, span, mono, testId }: { label: string; value?: string; span?: boolean; mono?: boolean; testId?: string }) {
  const empty = !value;
  return (
    <div className={span ? 'span-2' : undefined}>
      <dt>{label}</dt>
      <dd className={`${empty ? 'empty ' : ''}${mono ? 'mono' : ''}`} data-testid={testId}>
        {empty ? '—' : value}
      </dd>
    </div>
  );
}

/** COTRN01C: view one transaction (transaction CT01). */
export function TransactionView() {
  const s = useScreen<Fields>('COTRN01C');
  const [tranId, setTranId] = useState('');
  const f = s.state?.fields;

  useEffect(() => {
    if (!s.state) return;
    setTranId(s.state.fields?.tranIdIn ?? '');
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { tranIdIn: tranId }), [s, tranId]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const invalid = s.state?.messageColor === 'RED' && s.state.focus === 'tranIdIn';

  return (
    <ScreenFrame program="COTRN01C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>View Transaction</h1>
            <p className="lede">Every field of one transaction.</p>
          </div>
        </div>
        <form className="panel compact" noValidate onSubmit={submit}>
          <div className="search-bar">
            <Field id="tranIdIn" label="Enter Tran ID" value={tranId} onChange={setTranId} maxLength={16} inputMode="numeric" width="290px" placeholder="16 digits" invalid={invalid} />
            <button className="btn btn-primary" type="submit" disabled={s.busy}>
              Fetch <kbd>ENTER</kbd>
            </button>
          </div>
        </form>
        <section className="panel" aria-labelledby="s-tran" data-testid="details">
          <h2 className="section-label" id="s-tran">
            Transaction
          </h2>
          <dl className="kv cols-4">
            <Kv label="Transaction ID" value={f?.tranId} mono span testId="tranId" />
            <Kv label="Card Number" value={f?.cardNum} mono span testId="cardNum" />
            <Kv label="Type CD" value={f?.typeCd} mono />
            <Kv label="Category CD" value={f?.catCd} mono />
            <Kv label="Source" value={f?.source} span />
            <Kv label="Description" value={f?.description} span testId="description" />
            <Kv label="Amount" value={f?.amount ? money(f.amount) : ''} mono span testId="amount" />
            <Kv label="Orig Date" value={f?.origDate} mono />
            <Kv label="Proc Date" value={f?.procDate} mono />
            <Kv label="Merchant ID" value={f?.merchantId} mono />
            <Kv label="Merchant Name" value={f?.merchantName} span />
            <Kv label="Merchant City" value={f?.merchantCity} />
            <Kv label="Merchant Zip" value={f?.merchantZip} mono />
          </dl>
        </section>
      </div>
    </ScreenFrame>
  );
}
