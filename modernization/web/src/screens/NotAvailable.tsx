import { useCallback } from 'react';
import { useNavigate } from 'react-router';
import { ScreenFrame } from '../ScreenFrame';
import { useSession } from '../session';

/** Placeholder for programs whose screen is not built yet; F3 returns to the user's menu. */
export function NotAvailable({ program }: { program: string }) {
  const navigate = useNavigate();
  const user = useSession();
  const press = useCallback(
    (key: string) => {
      if (key === 'F3') navigate(`/screens/${user?.role === 'ADMIN' ? 'COADM01C' : 'COMEN01C'}`);
    },
    [navigate, user],
  );
  return (
    <ScreenFrame program={program} tran="" onKey={press} keys={[{ key: 'F3', label: 'Back' }]}>
      <div className="page">
        <div className="panel">
          <h1>{program}</h1>
          <p className="lede">This screen is not available in this build yet.</p>
        </div>
      </div>
    </ScreenFrame>
  );
}
