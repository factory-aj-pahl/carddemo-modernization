/** Display helpers shared by the screens. */

/** C-MASK as the server applies it to a 16-digit card number. */
export function maskCard(value: string): string {
  return /^\d{16}$/.test(value) ? '*'.repeat(12) + value.slice(12) : value;
}

/** Keeps what the user typed when the server sent back the masked form of the same value. */
export function keepTyped(typed: string, fromServer: string): string {
  return fromServer !== '' && maskCard(typed) === fromServer ? typed : fromServer;
}
