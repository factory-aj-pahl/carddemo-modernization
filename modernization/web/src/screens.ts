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
  COTRTLIC: {
    tran: 'CTLI',
    title: 'Maintain Transaction Type',
    keys: [
      { key: 'F2', label: 'Add' },
      { key: 'F3', label: 'Exit' },
      { key: 'F7', label: 'Page Up' },
      { key: 'F8', label: 'Page Dn' },
      { key: 'F10', label: 'Save' },
    ],
  },
  // The COTRTUPC key legend depends on the state (BR-COTRTUPC-26); the screen passes it to ScreenFrame.
  COTRTUPC: { tran: 'CTTU', title: 'Maintain Transaction Type', keys: [] },
};

/** Legend key text to the attention identifier the server expects. */
export function aid(key: string): `PF${number}` | 'ENTER' {
  return key === 'ENTER' ? 'ENTER' : (`PF${key.slice(1)}` as `PF${number}`);
}
