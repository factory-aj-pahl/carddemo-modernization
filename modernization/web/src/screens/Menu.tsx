import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { Field } from '../Field';
import { ScreenFrame } from '../ScreenFrame';
import { SCREENS, aid } from '../screens';
import { fieldInError, useScreen } from '../useScreen';

interface Fields {
  options: string[];
  option: string;
}

const LINE = /^(\d+)\.\s*(.*)$/;

/** COMEN01C main menu and COADM01C admin menu: same layout, different option catalog. */
export function Menu({ program }: { program: 'COMEN01C' | 'COADM01C' }) {
  const s = useScreen<Fields>(program);
  const [option, setOption] = useState('');

  useEffect(() => {
    if (s.state) setOption(s.state.fields?.option ?? '');
  }, [s.state]);

  const press = useCallback((key: string) => s.send(aid(key), { option }), [s, option]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  const options = (s.state?.fields?.options ?? []).map((line) => {
    const m = LINE.exec(line.trim());
    return m ? { num: m[1].padStart(2, '0'), name: m[2] } : { num: '', name: line.trim() };
  });
  const admin = program === 'COADM01C';

  return (
    <ScreenFrame program={program} message={s.error ?? s.state?.message} messageColor={s.error ? 'RED' : s.state?.messageColor} onKey={press} busy={s.busy}>
      <div className="page">
        <div className="page-head">
          <div>
            <h1>{SCREENS[program].title}</h1>
            <p className="lede">Choose a function, or type its number and press ENTER.</p>
          </div>
        </div>
        <form className="panel" noValidate onSubmit={submit}>
          <h2 className="section-label" id="opts">
            {admin ? 'Administrative functions' : 'Functions'}
          </h2>
          <ul className="menu-grid" aria-labelledby="opts">
            {options.map((o) => (
              <li key={o.num + o.name}>
                <button type="button" className="opt" onClick={() => s.send('ENTER', { option: o.num })} disabled={s.busy}>
                  <span className="opt-num">{o.num}</span>
                  <span>{o.name}</span>
                </button>
              </li>
            ))}
          </ul>
          <div className="select-row">
            <Field
              id="option"
              label="Please select an option"
              value={option}
              onChange={setOption}
              maxLength={2}
              inputMode="numeric"
              width="190px"
              placeholder={options.length ? `01–${String(options.length).padStart(2, '0')}` : undefined}
              invalid={fieldInError(s.state, 'option')}
            />
            <button className="btn btn-primary" type="submit" disabled={s.busy}>
              Continue <kbd>ENTER</kbd>
            </button>
          </div>
        </form>
      </div>
    </ScreenFrame>
  );
}
