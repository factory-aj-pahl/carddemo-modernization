import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { aid } from '../screens';
import { useScreen } from '../useScreen';

interface Row {
  select: string;
  type: string;
  description: string;
  selectable: boolean;
  selectInvalid: boolean;
  highlighted: boolean;
  descriptionEditable: boolean;
  descriptionInvalid: boolean;
}

interface Fields {
  page: string;
  typeFilter: string;
  descFilter: string;
  filtersLocked: boolean;
  typeFilterInvalid: boolean;
  descFilterInvalid: boolean;
  rows: Row[];
  info: string | null;
}

interface RowInput {
  select: string;
  description: string;
}

/** COTRTLIC: list, filter, update, and delete Db2 transaction types (transaction CTLI). */
export function TranTypeList() {
  const s = useScreen<Fields>('COTRTLIC');
  const [typeFilter, setTypeFilter] = useState('');
  const [descFilter, setDescFilter] = useState('');
  const [rows, setRows] = useState<RowInput[]>([]);
  const f = s.state?.fields;

  useEffect(() => {
    if (!s.state?.fields) return;
    const fields = s.state.fields;
    setTypeFilter(fields.typeFilter);
    setDescFilter(fields.descFilter);
    setRows(fields.rows.map((r) => ({ select: r.select, description: r.description })));
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { typeFilter, descFilter, rows }), [s, typeFilter, descFilter, rows]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const setRow = (i: number, patch: Partial<RowInput>) => setRows((old) => old.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const locked = f?.filtersLocked ?? false;

  return (
    <ScreenFrame program="COTRTLIC" message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Maintain Transaction Type</h1>
            <p className="lede">Type U to update or D to delete a row, then press ENTER; F10 saves.</p>
          </div>
          <span className="page-no">
            Page <strong data-testid="page">{f?.page ?? ''}</strong>
          </span>
        </div>
        <form className="panel" noValidate onSubmit={submit}>
          <div className="search-bar">
            <Field id="typeFilter" label="Type Filter" value={typeFilter} onChange={setTypeFilter} maxLength={2} inputMode="numeric" width="120px" readOnly={locked} aria-readonly={locked || undefined} invalid={f?.typeFilterInvalid} />
            <Field id="descFilter" label="Description Filter" value={descFilter} onChange={setDescFilter} maxLength={50} width="420px" readOnly={locked} aria-readonly={locked || undefined} invalid={f?.descFilterInvalid} />
            <button className="btn btn-primary" type="submit" disabled={s.busy}>
              Process <kbd>ENTER</kbd>
            </button>
          </div>
          <div className="list-wrap">
            <table className="list" aria-label="Transaction types">
              <thead>
                <tr>
                  <th scope="col">Select</th>
                  <th scope="col">Type</th>
                  <th scope="col">Description</th>
                </tr>
              </thead>
              <tbody>
                {(f?.rows ?? []).map((r, i) => {
                  const n = i + 1;
                  const input = rows[i] ?? { select: '', description: '' };
                  return (
                    <tr key={n} className={r.highlighted ? 'err' : r.type ? undefined : 'empty-row'} aria-current={r.highlighted ? 'true' : undefined} data-testid={`row${n}`}>
                      <td>
                        <input
                          className="input sel"
                          id={`select${n}`}
                          aria-label={`Select row ${n}`}
                          value={input.select}
                          maxLength={1}
                          autoComplete="off"
                          readOnly={!r.selectable}
                          aria-readonly={!r.selectable || undefined}
                          aria-invalid={r.selectInvalid || undefined}
                          onChange={(e) => setRow(i, { select: e.target.value.toUpperCase() })}
                        />
                      </td>
                      <td className="mono">{r.type}</td>
                      <td>
                        {r.descriptionEditable ? (
                          <input
                            className="input"
                            id={`desc${n}`}
                            aria-label={`Description row ${n}`}
                            value={input.description}
                            maxLength={50}
                            autoComplete="off"
                            aria-invalid={r.descriptionInvalid || undefined}
                            onChange={(e) => setRow(i, { description: e.target.value })}
                          />
                        ) : (
                          <span className="mono">{r.description}</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {f?.info ? (
            <p className="info-line" style={{ marginTop: 14 }} data-testid="info" data-raw={f.info}>
              {f.info.trim()}
            </p>
          ) : null}
        </form>
      </div>
    </ScreenFrame>
  );
}
