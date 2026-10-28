/** Display helpers shared by the screens. */

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

/** An edited COBOL amount such as `+00000504.77` as currency; anything unreadable is shown as sent. */
export function money(text: string | undefined): string {
  if (!text || !text.trim()) return '';
  const n = Number(text.trim().replace(/,/g, ''));
  return Number.isFinite(n) ? usd.format(n) : text;
}

/** C-MASK as the server applies it to a 16-digit card number. */
export function maskCard(value: string): string {
  return /^\d{16}$/.test(value) ? '*'.repeat(12) + value.slice(12) : value;
}

/** Keeps what the user typed when the server sent back the masked form of the same value. */
export function keepTyped(typed: string, fromServer: string): string {
  return fromServer !== '' && maskCard(typed) === fromServer ? typed : fromServer;
}
