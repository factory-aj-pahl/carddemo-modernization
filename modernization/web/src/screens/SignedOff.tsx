import { type FormEvent, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { ScreenFrame } from '../ScreenFrame';
import type { Flash } from '../useScreen';

/** After PF3 on the sign-on screen: COSGN00C's plain-text farewell, then a fresh sign-on on ENTER. */
export function SignedOff() {
  const navigate = useNavigate();
  const flash = (useLocation().state as Flash | null) ?? {};
  const press = useCallback(
    (key: string) => {
      if (key === 'ENTER') navigate('/screens/COSGN00C');
    },
    [navigate],
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    press('ENTER');
  };
  return (
    <ScreenFrame program="COSGN00C" onKey={press} keys={[{ key: 'ENTER', label: 'Sign on again' }]}>
      <form className="thanks panel" noValidate onSubmit={submit}>
        <h1>Signed off</h1>
        <p className="lede" style={{ whiteSpace: 'pre-wrap' }} data-testid="farewell">
          {flash.message ?? ''}
        </p>
        <p style={{ margin: '20px 0 0' }}>
          <button className="btn btn-primary" type="submit" autoFocus>
            Sign on again <kbd>ENTER</kbd>
          </button>
        </p>
      </form>
    </ScreenFrame>
  );
}
