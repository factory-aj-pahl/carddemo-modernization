import { type FormEvent, Fragment, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { type KeyLegend, SCREENS, aid } from '../screens';
import { useScreen } from '../useScreen';

type Mode = 'SEARCH' | 'S' | 'E' | 'N' | 'C' | 'L' | 'F';

interface Fields {
  mode: Mode;
  values: Record<string, string>;
  editable: string[];
  errorFields: string[];
  info: string;
  saveKey: boolean;
  cancelKey: boolean;
}

/** Input lengths of CACTUPA; amounts are widened to the 10-integer-digit rendering. */
const LENGTHS: Record<string, number> = {
  accountId: 11, active: 1, openYear: 4, openMonth: 2, openDay: 2, creditLimit: 17, expYear: 4, expMonth: 2, expDay: 2,
  cashCreditLimit: 17, risYear: 4, risMonth: 2, risDay: 2, currentBalance: 17, cycleCredit: 17, cycleDebit: 17,
  group: 10, customerId: 9, ssn1: 3, ssn2: 2, ssn3: 4, dobYear: 4, dobMonth: 2, dobDay: 2, fico: 3, firstName: 25,
  middleName: 25, lastName: 25, addr1: 50, addr2: 50, state: 2, zip: 5, city: 50, country: 3, phone1a: 3, phone1b: 3,
  phone1c: 4, phone2a: 3, phone2b: 3, phone2c: 4, govtId: 20, eft: 10, primary: 1,
};

const EMPTY: Record<string, string> = Object.fromEntries(Object.keys(LENGTHS).map((k) => [k, '']));

const NUMERIC = new Set(['accountId', 'openYear', 'openMonth', 'openDay', 'expYear', 'expMonth', 'expDay', 'risYear', 'risMonth', 'risDay', 'dobYear', 'dobMonth', 'dobDay', 'fico', 'zip', 'phone1a', 'phone1b', 'phone1c', 'phone2a', 'phone2b', 'phone2c', 'eft']);

function keepLast4(v: string): string {
  return v.length <= 4 ? v : '*'.repeat(v.length - 4) + v.slice(-4);
}

/**
 * C-MASK: the server echoes typed SSN parts and government id masked. While changes are pending
 * (not on a fresh snapshot) the browser keeps what the user typed when the echo is its masked form.
 */
function keepTypedSecrets(mode: Mode, typed: Record<string, string>, server: Record<string, string>): Record<string, string> {
  if (mode === 'SEARCH' || mode === 'S') return server;
  const out = { ...server };
  if (server.ssn1 === '***' && typed.ssn1?.length === 3) out.ssn1 = typed.ssn1;
  if (server.ssn2 === '**' && typed.ssn2?.length === 2) out.ssn2 = typed.ssn2;
  if (server.govtId && typed.govtId && server.govtId === keepLast4(typed.govtId)) out.govtId = typed.govtId;
  return out;
}

interface InputProps {
  name: string;
  label: string;
  width?: string;
  placeholder?: string;
  upper?: boolean;
  /** Group label read before the part label. */
  labelPrefix?: string;
  labelHidden?: boolean;
}

/** COACTUPC: update an account and its customer (transaction CAUP). */
export function AccountUpdate() {
  const s = useScreen<Fields>('COACTUPC');
  const [form, setForm] = useState<Record<string, string>>(EMPTY);
  const f = s.state?.fields;
  const mode: Mode = f?.mode ?? 'SEARCH';

  useEffect(() => {
    if (!s.state?.fields) return;
    const next = s.state.fields;
    setForm((prev) => keepTypedSecrets(next.mode, prev, { ...EMPTY, ...next.values }));
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { fields: form }), [s, form]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const set = (name: string) => (v: string) => setForm((prev) => ({ ...prev, [name]: v }));
  const editable = new Set(f?.editable ?? ['accountId']);
  const errors = new Set(f?.errorFields ?? []);
  const search = mode === 'SEARCH';

  const input = ({ name, label, width, placeholder, upper, labelPrefix, labelHidden }: InputProps) => {
    const readOnly = !editable.has(name);
    return (
      <Field
        id={name}
        label={label}
        value={form[name] ?? ''}
        onChange={set(name)}
        maxLength={LENGTHS[name]}
        inputMode={NUMERIC.has(name) ? 'numeric' : undefined}
        width={width}
        placeholder={placeholder}
        upper={upper}
        readOnly={readOnly}
        aria-readonly={readOnly ? true : undefined}
        labelPrefix={labelPrefix}
        labelHidden={labelHidden}
        invalid={errors.has(name)}
      />
    );
  };

  /**
   * A field entered in parts (date, SSN, phone): the BMS label is the one visible label line, so the inputs line up
   * with single fields in the same row; each part keeps a full accessible name such as "SSN first 3".
   */
  const parts = (legend: string, items: { name: string; label: string; width: string; placeholder?: string }[], sep: string) => {
    return (
      <fieldset className="parts">
        <legend>{legend}</legend>
        <div className="parts-row">
          {items.map((it, i) => (
            <Fragment key={it.name}>
              {i > 0 ? (
                <span aria-hidden="true" className="parts-sep">
                  {sep}
                </span>
              ) : null}
              {input({ name: it.name, label: it.label, width: it.width, placeholder: it.placeholder, labelPrefix: legend, labelHidden: true })}
            </Fragment>
          ))}
        </div>
      </fieldset>
    );
  };
  const date = (legend: string, prefix: string) =>
    parts(
      legend,
      [
        { name: `${prefix}Year`, label: 'year', width: '84px', placeholder: 'YYYY' },
        { name: `${prefix}Month`, label: 'month', width: '62px', placeholder: 'MM' },
        { name: `${prefix}Day`, label: 'day', width: '62px', placeholder: 'DD' },
      ],
      '-',
    );
  const phone = (legend: string, prefix: string) =>
    parts(
      legend,
      [
        { name: `${prefix}a`, label: 'area code', width: '78px' },
        { name: `${prefix}b`, label: 'prefix', width: '72px' },
        { name: `${prefix}c`, label: 'line number', width: '92px' },
      ],
      '-',
    );

  const base = SCREENS.COACTUPC.keys;
  const keys: KeyLegend[] = [
    ...base,
    ...(f?.saveKey ? [{ key: 'F5', label: 'Save' }] : []),
    ...(f?.cancelKey ? [{ key: 'F12', label: 'Cancel' }] : []),
  ];
  const grid = { display: 'grid', gridTemplateColumns: 'repeat(2,minmax(0,1fr))', gap: '14px 20px' } as const;

  return (
    <ScreenFrame program="COACTUPC" keys={keys} message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page wide">
        <div className="page-head">
          <div>
            <h1>Update Account</h1>
            <p className="lede">Change account and customer details, then confirm with F5.</p>
          </div>
        </div>
        <form noValidate onSubmit={submit} data-testid="form" data-mode={mode}>
          <div className="panel compact">
            <div className="search-bar">
              {input({ name: 'accountId', label: 'Account Number', width: '240px', placeholder: '11 digits' })}
              {/* In the confirm state the step the key bar offers is F5 Save, so Save is the primary button. */}
              <button className={`btn${f?.saveKey ? '' : ' btn-primary'}`} type="submit" disabled={s.busy}>
                Process <kbd>ENTER</kbd>
              </button>
              <p className="info-line" style={{ marginBottom: 0 }} data-testid="info">
                {f?.info}
              </p>
              <div style={{ marginLeft: 'auto', alignSelf: 'flex-end', display: 'flex', gap: 10 }}>
                {f?.saveKey ? (
                  <button className="btn btn-primary" type="button" onClick={() => press('F5')} disabled={s.busy}>
                    Save <kbd>F5</kbd>
                  </button>
                ) : null}
                {f?.cancelKey ? (
                  <button className="btn" type="button" onClick={() => press('F12')} disabled={s.busy}>
                    Cancel <kbd>F12</kbd>
                  </button>
                ) : null}
              </div>
            </div>
          </div>
          <div className="grid-2" style={{ gridTemplateColumns: '5fr 7fr' }} data-testid="details">
            <section className="panel" aria-labelledby="s-acct">
              <h2 className="section-label" id="s-acct">
                Account
              </h2>
              <div style={grid}>
                {input({ name: 'active', label: 'Active Y/N', width: '110px' })}
                {input({ name: 'group', label: 'Account Group' })}
                <div style={{ gridColumn: 'span 2' }}>{date('Opened', 'open')}</div>
                <div style={{ gridColumn: 'span 2' }}>{date('Expiry', 'exp')}</div>
                <div style={{ gridColumn: 'span 2' }}>{date('Reissue', 'ris')}</div>
                {input({ name: 'creditLimit', label: 'Credit Limit' })}
                {input({ name: 'cashCreditLimit', label: 'Cash credit Limit' })}
                {input({ name: 'currentBalance', label: 'Current Balance' })}
                {input({ name: 'cycleCredit', label: 'Current Cycle Credit' })}
                {input({ name: 'cycleDebit', label: 'Current Cycle Debit' })}
              </div>
            </section>
            <section className="panel" aria-labelledby="s-cust">
              <h2 className="section-label" id="s-cust">
                Customer Details
              </h2>
              <div style={grid}>
                {input({ name: 'customerId', label: 'Customer id', width: '160px' })}
                {parts(
                  'SSN',
                  [
                    { name: 'ssn1', label: 'first 3', width: '70px', placeholder: search ? '999' : undefined },
                    { name: 'ssn2', label: '4th & 5th', width: '74px', placeholder: search ? '99' : undefined },
                    { name: 'ssn3', label: 'last 4', width: '84px', placeholder: search ? '9999' : undefined },
                  ],
                  '-',
                )}
                {date('Date of birth', 'dob')}
                {input({ name: 'fico', label: 'FICO Score', width: '110px' })}
                {input({ name: 'firstName', label: 'First Name' })}
                {input({ name: 'middleName', label: 'Middle Name' })}
                {input({ name: 'lastName', label: 'Last Name' })}
                <fieldset className="parts" style={{ gridColumn: 'span 2' }}>
                  <legend>Address</legend>
                  <div style={{ display: 'grid', gap: 8 }}>
                    {input({ name: 'addr1', label: 'line 1', labelPrefix: 'Address', labelHidden: true })}
                    {input({ name: 'addr2', label: 'line 2', labelPrefix: 'Address', labelHidden: true })}
                  </div>
                </fieldset>
                {input({ name: 'city', label: 'City' })}
                {input({ name: 'state', label: 'State', width: '90px' })}
                {input({ name: 'zip', label: 'Zip', width: '110px' })}
                {input({ name: 'country', label: 'Country', width: '90px' })}
                {phone('Phone 1', 'phone1')}
                {phone('Phone 2', 'phone2')}
                {input({ name: 'govtId', label: 'Government Issued Id Ref' })}
                {input({ name: 'eft', label: 'EFT Account Id' })}
                {input({ name: 'primary', label: 'Primary Card Holder Y/N', width: '190px' })}
              </div>
            </section>
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
