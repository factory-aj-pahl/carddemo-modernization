import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Run {
  runId: string;
  reportType: string;
  startDate: string;
  endDate: string;
  userId: string;
  requestedAt: string;
  state: string;
  downloadUrl?: string;
}

interface Fields {
  reportType: string;
  startMonth: string;
  startDay: string;
  startYear: string;
  endMonth: string;
  endDay: string;
  endYear: string;
  confirm: string;
  runs: Run[];
}

type Input = Omit<Fields, 'runs'>;
type Name = keyof Input;

const EMPTY: Input = { reportType: '', startMonth: '', startDay: '', startYear: '', endMonth: '', endDay: '', endYear: '', confirm: '' };

const TYPES = [
  { id: 'monthly', label: 'Monthly', hint: 'Current Month' },
  { id: 'yearly', label: 'Yearly', hint: 'Current Year' },
  { id: 'custom', label: 'Custom', hint: 'Date Range' },
];

const STATE_LABEL: Record<string, string> = { queued: 'Queued', running: 'Running', success: 'Completed', failed: 'Failed' };

/** CORPT00C: request a transaction report (transaction CR00) and follow its runs. */
export function TransactionReports() {
  const s = useScreen<Fields>('CORPT00C');
  const [form, setForm] = useState<Input>(EMPTY);
  const [runs, setRuns] = useState<Run[]>([]);

  useEffect(() => {
    if (!s.state) return;
    const { runs: r, ...rest } = { ...EMPTY, runs: [], ...(s.state.fields ?? {}) } as Fields;
    setForm({ ...EMPTY, ...rest });
    setRuns(r ?? []);
  }, [s.state]);

  const set = (name: Name) => (v: string) => setForm((prev) => ({ ...prev, [name]: v }));
  const press = useCallback((key: string) => s.send(aid(key), { ...form }), [s, form]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const invalid = (id: string) => s.state?.messageColor === 'RED' && s.state.focus === id;
  const refresh = () => {
    fetch('/api/reports', { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
      .then((r) => (r.ok ? (r.json() as Promise<Run[]>) : Promise.reject(new Error(String(r.status)))))
      .then(setRuns)
      .catch(() => undefined);
  };

  const date = (legend: string, prefix: 'start' | 'end') => (
    <fieldset className="row" style={{ border: 0, padding: 0, margin: 0, alignItems: 'flex-end', gap: 8 }} data-testid={`${prefix}-date`}>
      <legend className="section-label" style={{ padding: 0, marginBottom: 6 }}>
        {legend} <span className="hint">(MM/DD/YYYY)</span>
      </legend>
      <Field id={`${prefix}Month`} label="Month" value={form[`${prefix}Month`]} onChange={set(`${prefix}Month`)} maxLength={2} inputMode="numeric" width="70px" placeholder="MM" invalid={invalid(`${prefix}Month`)} />
      <span aria-hidden="true" style={{ paddingBottom: 10 }}>/</span>
      <Field id={`${prefix}Day`} label="Day" value={form[`${prefix}Day`]} onChange={set(`${prefix}Day`)} maxLength={2} inputMode="numeric" width="70px" placeholder="DD" invalid={invalid(`${prefix}Day`)} />
      <span aria-hidden="true" style={{ paddingBottom: 10 }}>/</span>
      <Field id={`${prefix}Year`} label="Year" value={form[`${prefix}Year`]} onChange={set(`${prefix}Year`)} maxLength={4} inputMode="numeric" width="90px" placeholder="YYYY" invalid={invalid(`${prefix}Year`)} />
    </fieldset>
  );

  return (
    <ScreenFrame program="CORPT00C" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Transaction Reports</h1>
            <p className="lede">Request the transaction detail report for this month, this year, or a date range.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit} data-testid="form">
          <fieldset style={{ border: 0, padding: 0, margin: 0 }} aria-invalid={invalid('monthly') ? true : undefined}>
            <legend className="section-label" style={{ padding: 0, marginBottom: 8 }}>
              Report type
            </legend>
            <div className="row" style={{ gap: 24 }}>
              {TYPES.map((t) => (
                <label key={t.id} htmlFor={t.id} className="row" style={{ gap: 8, alignItems: 'center', cursor: 'pointer' }}>
                  <input type="radio" id={t.id} name="reportType" value={t.id} checked={form.reportType === t.id} onChange={() => set('reportType')(t.id)} />
                  <span>
                    {t.label} <span className="hint">({t.hint})</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          <hr className="divider" />
          <div className="row" style={{ gap: 40, flexWrap: 'wrap' }}>
            {date('Start Date', 'start')}
            {date('End Date', 'end')}
          </div>
          <p className="hint" style={{ marginTop: 8 }}>
            The dates are used only for a Custom report.
          </p>
          <hr className="divider" />
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <Field id="confirm" label="The Report will be submitted for printing. Please confirm:" value={form.confirm} onChange={set('confirm')} maxLength={1} width="auto" hint="(Y/N)" upper inputClass="sel" invalid={invalid('confirm')} />
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 22 }}>
            <button className="btn btn-primary" type="submit" disabled={s.busy}>
              Continue <kbd>ENTER</kbd>
            </button>
          </div>
        </form>
        <section className="panel" style={{ marginTop: 20 }} aria-labelledby="runs-title">
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <h2 id="runs-title" className="section-label" style={{ margin: 0 }}>
              Report runs
            </h2>
            <button className="btn btn-sm" type="button" onClick={refresh} disabled={s.busy}>
              Refresh status
            </button>
          </div>
          <div className="list-wrap" style={{ marginTop: 12 }}>
            <table className="list" data-testid="runs">
              <caption className="sr-only">Recent transaction report runs</caption>
              <thead>
                <tr>
                  <th scope="col">Run ID</th>
                  <th scope="col">Report</th>
                  <th scope="col">Range</th>
                  <th scope="col">User</th>
                  <th scope="col">Requested</th>
                  <th scope="col">Status</th>
                  <th scope="col">Report file</th>
                </tr>
              </thead>
              <tbody>
                {runs.length === 0 ? (
                  <tr>
                    <td colSpan={7} className="hint">
                      No reports requested yet.
                    </td>
                  </tr>
                ) : (
                  runs.map((r) => (
                    <tr key={r.runId} data-testid="run">
                      <td className="mono">{r.runId}</td>
                      <td>{r.reportType}</td>
                      <td className="mono">
                        {r.startDate} to {r.endDate}
                      </td>
                      <td className="mono">{r.userId}</td>
                      <td className="mono">{r.requestedAt}</td>
                      <td>
                        <span className={`pill${r.state === 'success' ? ' yes' : ''}`} data-testid="run-state">
                          {STATE_LABEL[r.state] ?? r.state}
                        </span>
                      </td>
                      <td>
                        {r.downloadUrl ? (
                          <a href={r.downloadUrl} download className="btn-link">
                            Download<span className="sr-only"> report {r.runId}</span>
                          </a>
                        ) : (
                          <span className="hint">Not ready</span>
                        )}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </ScreenFrame>
  );
}
