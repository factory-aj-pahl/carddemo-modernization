/** Screen registry: program name -> transaction ID, title, and key legend (from each program's BMS map). */

export interface KeyLegend {
  key: string; // 'ENTER' or 'F3' etc.
  label: string;
}

export interface ScreenInfo {
  tran: string;
  title: string;
  keys: KeyLegend[];
}

export const SCREENS: Record<string, ScreenInfo> = {
  COSGN00C: { tran: 'CC00', title: 'Sign on', keys: [{ key: 'ENTER', label: 'Sign-on' }, { key: 'F3', label: 'Exit' }] },
  COMEN01C: { tran: 'CM00', title: 'Main Menu', keys: [{ key: 'ENTER', label: 'Continue' }, { key: 'F3', label: 'Exit' }] },
  COADM01C: { tran: 'CA00', title: 'Admin Menu', keys: [{ key: 'ENTER', label: 'Continue' }, { key: 'F3', label: 'Exit' }] },
  COBIL00C: {
    tran: 'CB00',
    title: 'Bill Payment',
    keys: [{ key: 'ENTER', label: 'Continue' }, { key: 'F3', label: 'Back' }, { key: 'F4', label: 'Clear' }],
  },
  COACTVWC: { tran: 'CAVW', title: 'View Account', keys: [{ key: 'ENTER', label: 'View' }, { key: 'F3', label: 'Exit' }] },
  COACTUPC: {
    tran: 'CAUP',
    title: 'Update Account',
    // F5=Save and F12=Cancel are added by the screen when the server shows them (BR-COACTUPC-21).
    keys: [{ key: 'ENTER', label: 'Process' }, { key: 'F3', label: 'Exit' }],
  },
  COTRN00C: {
    tran: 'CT00',
    title: 'List Transactions',
    keys: [
      { key: 'ENTER', label: 'Continue' },
      { key: 'F3', label: 'Back' },
      { key: 'F7', label: 'Backward' },
      { key: 'F8', label: 'Forward' },
    ],
  },
  COTRN01C: {
    tran: 'CT01',
    title: 'View Transaction',
    keys: [
      { key: 'ENTER', label: 'Fetch' },
      { key: 'F3', label: 'Back' },
      { key: 'F4', label: 'Clear' },
      { key: 'F5', label: 'Browse Tran.' },
    ],
  },
  COTRN02C: {
    tran: 'CT02',
    title: 'Add Transaction',
    keys: [
      { key: 'ENTER', label: 'Continue' },
      { key: 'F3', label: 'Back' },
      { key: 'F4', label: 'Clear' },
      { key: 'F5', label: 'Copy Last Tran.' },
    ],
  },
  CORPT00C: { tran: 'CR00', title: 'Transaction Reports', keys: [{ key: 'ENTER', label: 'Continue' }, { key: 'F3', label: 'Back' }] },
};

/** Legend key text to the attention identifier the server expects. */
export function aid(key: string): `PF${number}` | 'ENTER' {
  return key === 'ENTER' ? 'ENTER' : (`PF${key.slice(1)}` as `PF${number}`);
}
