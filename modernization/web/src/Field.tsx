import type { InputHTMLAttributes } from 'react';

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
}

/** Labelled input in the prototype style; length limits mirror the BMS field lengths. */
export function Field({ id, label, value, onChange, hint, invalid, upper, width, inputClass, ...rest }: Props) {
  return (
    <div className="field" style={width ? { width } : undefined}>
      <label htmlFor={id}>{label}</label>
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
