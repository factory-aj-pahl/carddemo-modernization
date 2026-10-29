import type { InputHTMLAttributes, TextareaHTMLAttributes } from 'react';

interface Props extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value'> {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  hint?: string;
  /** The server put the cursor here with an error message: mark the field invalid. */
  invalid?: boolean;
  upper?: boolean;
  width?: string;
  inputClass?: string;
  /** The label is read by assistive technology only; a visible group label names the field. */
  labelHidden?: boolean;
  /** Group label (a part's legend) read before the part label, so each part has a complete name. */
  labelPrefix?: string;
}

/** Labelled input in the prototype style; length limits mirror the BMS field lengths. */
export function Field({ id, label, value, onChange, hint, invalid, upper, width, inputClass, labelHidden, labelPrefix, ...rest }: Props) {
  return (
    <div className="field" style={width ? { width } : undefined}>
      <label htmlFor={id} className={labelHidden ? 'sr-only' : undefined}>
        {labelPrefix ? <span className="sr-only">{labelPrefix} </span> : null}
        {label}
      </label>
      <input
        className={`input${inputClass ? ' ' + inputClass : ''}`}
        id={id}
        name={id}
        value={value}
        autoComplete="off"
        spellCheck={false}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={hint ? `${id}-hint` : undefined}
        onChange={(e) => onChange(upper ? e.target.value.toUpperCase() : e.target.value)}
        {...rest}
      />
      {hint ? (
        <span className="hint" id={`${id}-hint`}>
          {hint}
        </span>
      ) : null}
    </div>
  );
}

interface AreaProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'onChange' | 'value'> {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  hint?: string;
  invalid?: boolean;
  width?: string;
}

/** A labelled field whose value can be longer than one line; ENTER submits the form as in a single-line field. */
export function AreaField({ id, label, value, onChange, hint, invalid, width, ...rest }: AreaProps) {
  return (
    <div className="field" style={width ? { width } : undefined}>
      <label htmlFor={id}>
        {label}
      </label>
      <textarea
        className="input"
        id={id}
        name={id}
        value={value}
        rows={2}
        autoComplete="off"
        spellCheck={false}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={hint ? `${id}-hint` : undefined}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key !== 'Enter' || e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) return;
          e.preventDefault();
          e.currentTarget.form?.requestSubmit();
        }}
        {...rest}
      />
      {hint ? (
        <span className="hint" id={`${id}-hint`}>
          {hint}
        </span>
      ) : null}
    </div>
  );
}
