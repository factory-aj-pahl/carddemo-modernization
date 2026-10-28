import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { type KeyLegend, aid } from '../screens';
import { useScreen } from '../useScreen';

interface Keys {
  enterExit: boolean;
  delete: boolean;
  save: boolean;
  cancel: boolean;
}

interface Abend {
  code: string;
  culprit: string;
  reason: string;
  message: string;
}

interface Fields {
  typeCode: string;
  description: string;
  typeEditable: boolean;
  descriptionEditable: boolean;
  typeInvalid: boolean;
  descriptionInvalid: boolean;
  info: string | null;
  keys: Keys;
  abend: Abend | null;
}

/** BR-COTRTUPC-26: only the keys the current state accepts are shown. */
function legend(k?: Keys): KeyLegend[] {
  if (!k) return [];
  const out: KeyLegend[] = [];
  if (k.enterExit) out.push({ key: 'ENTER', label: 'Process' }, { key: 'F3', label: 'Exit' });
  if (k.delete) out.push({ key: 'F4', label: 'Delete' });
  if (k.save) out.push({ key: 'F5', label: 'Save' });
  if (k.cancel) out.push({ key: 'F12', label: 'Cancel' });
  return out;
}

/** COTRTUPC: look up, add, update, or delete one Db2 transaction type (transaction CTTU). */
export function TranTypeMaint() {
  const s = useScreen<Fields>('COTRTUPC');
  const [typeCode, setTypeCode] = useState('');
  const [description, setDescription] = useState('');
  const f = s.state?.fields;

  useEffect(() => {
    if (!s.state?.fields) return;
    setTypeCode(s.state.fields.typeCode);
    setDescription(s.state.fields.description);
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { typeCode, description }), [s, typeCode, description]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const abend = f?.abend;

  return (
    <ScreenFrame
      program="COTRTUPC"
      message={s.error ?? s.state?.message}
      messageColor={s.error ? 'RED' : s.state?.messageColor}
      onKey={press}
      busy={s.busy}
      keys={abend ? [{ key: 'ENTER', label: 'Restart' }] : legend(f?.keys)}
    >
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Maintain Transaction Type</h1>
            <p className="lede">Enter a transaction type to change, add, or delete it.</p>
          </div>
        </div>
        {abend ? (
          <form className="panel" noValidate onSubmit={submit} data-testid="abend">
            <h2 className="section-label">Abnormal end</h2>
            <dl className="kv cols-2">
              <div>
                <dt>Abend code</dt>
                <dd className="mono">{abend.code}</dd>
              </div>
              <div>
                <dt>Program</dt>
                <dd className="mono">{abend.culprit}</dd>
              </div>
              <div className="span-2">
                <dt>Message</dt>
                <dd>{abend.message}</dd>
              </div>
            </dl>
            <button className="btn btn-primary" type="submit" style={{ marginTop: 22 }} disabled={s.busy}>
              Restart <kbd>ENTER</kbd>
            </button>
          </form>
        ) : (
          <form className="panel" noValidate onSubmit={submit}>
            <div className="row">
              <Field
                id="typeCode"
                label="Transaction Type"
                value={typeCode}
                onChange={setTypeCode}
                maxLength={2}
                inputMode="numeric"
                width="160px"
                readOnly={!f?.typeEditable}
                aria-readonly={!f?.typeEditable || undefined}
                invalid={f?.typeInvalid}
              />
              <Field
                id="description"
                label="Description"
                value={description}
                onChange={setDescription}
                maxLength={50}
                width="520px"
                readOnly={!f?.descriptionEditable}
                aria-readonly={!f?.descriptionEditable || undefined}
                invalid={f?.descriptionInvalid}
              />
              <button className="btn btn-primary" type="submit" disabled={s.busy}>
                Process <kbd>ENTER</kbd>
              </button>
            </div>
            {f?.info ? (
              <p className="info-line" style={{ marginTop: 18 }} data-testid="info" data-raw={f.info}>
                {f.info.trim()}
              </p>
            ) : null}
          </form>
        )}
      </div>
    </ScreenFrame>
  );
}
