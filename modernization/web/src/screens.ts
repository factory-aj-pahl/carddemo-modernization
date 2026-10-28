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
  COUSR00C: {
    tran: 'CU00',
    title: 'List Users',
    keys: [
      { key: 'ENTER', label: 'Continue' },
      { key: 'F3', label: 'Back' },
      { key: 'F7', label: 'Backward' },
      { key: 'F8', label: 'Forward' },
    ],
  },
  COUSR01C: {
    tran: 'CU01',
    title: 'Add User',
    keys: [
      { key: 'ENTER', label: 'Add User' },
      { key: 'F3', label: 'Back' },
      { key: 'F4', label: 'Clear' },
      { key: 'F12', label: 'Exit' },
    ],
  },
  COUSR02C: {
    tran: 'CU02',
    title: 'Update User',
    keys: [
      { key: 'ENTER', label: 'Fetch' },
      { key: 'F3', label: 'Save&Exit' },
      { key: 'F4', label: 'Clear' },
      { key: 'F5', label: 'Save' },
      { key: 'F12', label: 'Cancel' },
    ],
  },
  COUSR03C: {
    tran: 'CU03',
    title: 'Delete User',
    keys: [
      { key: 'ENTER', label: 'Fetch' },
      { key: 'F3', label: 'Back' },
      { key: 'F4', label: 'Clear' },
      { key: 'F5', label: 'Delete' },
    ],
  },
  COCRDLIC: {
    tran: 'CCLI',
    title: 'List Credit Cards',
    keys: [
      { key: 'F3', label: 'Exit' },
      { key: 'F7', label: 'Backward' },
      { key: 'F8', label: 'Forward' },
    ],
  },
  COCRDSLC: {
    tran: 'CCDL',
    title: 'View Credit Card Detail',
    keys: [
      { key: 'ENTER', label: 'Search Cards' },
      { key: 'F3', label: 'Exit' },
    ],
  },
  COCRDUPC: {
    tran: 'CCUP',
    title: 'Update Credit Card Details',
    keys: [
      { key: 'ENTER', label: 'Process' },
      { key: 'F3', label: 'Exit' },
    ],
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
