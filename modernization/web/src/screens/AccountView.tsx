import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Fields {
  accountId: string;
  active: string;
  opened: string;
  expiry: string;
  reissue: string;
  creditLimit: string;
  cashCreditLimit: string;
  currentBalance: string;
  cycleCredit: string;
  cycleDebit: string;
  group: string;
  customerId: string;
  ssn: string;
  dob: string;
  fico: string;
  firstName: string;
  middleName: string;
  lastName: string;
  addr1: string;
  addr2: string;
  city: string;
  state: string;
  zip: string;
  country: string;
  phone1: string;
  phone2: string;
  govtId: string;
  eft: string;
  primary: string;
  info: string;
  accountInError: boolean;
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

function Amount({ label, value, strong, testId }: { label: string; value?: string; strong?: boolean; testId: string }) {
  return (
    <tr className={strong ? 'strong' : undefined}>
      <th scope="row">{label}</th>
      {/* The edited COBOL amount is shown as sent, blanks included (+ZZZ,ZZZ,ZZZ.99). */}
      <td data-testid={testId} style={{ whiteSpace: 'pre' }}>
        {value || '—'}
      </td>
    </tr>
  );
}

/** COACTVWC: view an account and its customer (transaction CAVW). */
export function AccountView() {
  const s = useScreen<Fields>('COACTVWC');
  const [accountId, setAccountId] = useState('');
  const f = s.state?.fields;

  useEffect(() => {
    if (!s.state) return;
    setAccountId(s.state.fields?.accountId ?? '');
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { accountId }), [s, accountId]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const address = [f?.addr1, f?.addr2].filter((v) => v).join(', ');

  return (
    <ScreenFrame program="COACTVWC" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page wide">
        <div className="page-head">
          <div>
            <h1>View Account</h1>
            <p className="lede">Account and customer details for one of your accounts.</p>
          </div>
        </div>
        <form className="panel compact" noValidate onSubmit={submit}>
          <div className="search-bar">
            <Field id="accountId" label="Account Number" value={accountId} onChange={setAccountId} maxLength={11} inputMode="numeric" width="240px" placeholder="11 digits" invalid={!!f?.accountInError} />
            <button className="btn btn-primary" type="submit" disabled={s.busy}>
              View <kbd>ENTER</kbd>
            </button>
            <p className="info-line" style={{ marginBottom: 0 }} data-testid="info">
              {f?.info}
            </p>
            <div style={{ marginLeft: 'auto', alignSelf: 'center', textAlign: 'right' }}>
              <div className="hint">Active Y/N</div>
              {f?.active ? (
                <span className={`pill${f.active === 'Y' ? ' yes' : ''}`} data-testid="active">
                  {f.active}
                </span>
              ) : (
                <span className="hint">—</span>
              )}
            </div>
          </div>
        </form>
        <div className="grid-2" data-testid="details">
          <section className="panel" aria-labelledby="s-acct">
            <h2 className="section-label" id="s-acct">
              Account
            </h2>
            <dl className="kv cols-2">
              <Kv label="Opened" value={f?.opened} mono testId="opened" />
              <Kv label="Expiry" value={f?.expiry} mono testId="expiry" />
              <Kv label="Reissue" value={f?.reissue} mono testId="reissue" />
              <Kv label="Account Group" value={f?.group} mono testId="group" />
            </dl>
            <table className="amounts" style={{ marginTop: 18 }}>
              <caption className="sr-only">Balances and limits</caption>
              <tbody>
                <Amount label="Credit Limit" value={f?.creditLimit} testId="creditLimit" />
                <Amount label="Cash Credit Limit" value={f?.cashCreditLimit} testId="cashCreditLimit" />
                <Amount label="Current Balance" value={f?.currentBalance} strong testId="currentBalance" />
                <Amount label="Current Cycle Credit" value={f?.cycleCredit} testId="cycleCredit" />
                <Amount label="Current Cycle Debit" value={f?.cycleDebit} testId="cycleDebit" />
              </tbody>
            </table>
          </section>
          <section className="panel" aria-labelledby="s-cust">
            <h2 className="section-label" id="s-cust">
              Customer Details
            </h2>
            <dl className="kv cols-4">
              <Kv label="Customer ID" value={f?.customerId} mono testId="customerId" />
              <Kv label="SSN" value={f?.ssn} mono testId="ssn" />
              <Kv label="Date of Birth" value={f?.dob} mono testId="dob" />
              <Kv label="FICO Score" value={f?.fico} mono testId="fico" />
              <Kv label="First Name" value={f?.firstName} testId="firstName" />
              <Kv label="Middle Name" value={f?.middleName} testId="middleName" />
              <Kv label="Last Name" value={f?.lastName} span testId="lastName" />
              <Kv label="Address" value={address} span testId="address" />
              <Kv label="City" value={f?.city} testId="city" />
              <Kv label="State" value={f?.state} testId="state" />
              <Kv label="Zip" value={f?.zip} mono testId="zip" />
              <Kv label="Country" value={f?.country} testId="country" />
              <Kv label="Phone 1" value={f?.phone1} mono testId="phone1" />
              <Kv label="Phone 2" value={f?.phone2} mono testId="phone2" />
              <Kv label="Government Issued Id Ref" value={f?.govtId} span mono testId="govtId" />
              <Kv label="EFT Account Id" value={f?.eft} span mono testId="eft" />
              <Kv label="Primary Card Holder Y/N" value={f?.primary} span testId="primary" />
            </dl>
          </section>
        </div>
      </div>
    </ScreenFrame>
  );
}
