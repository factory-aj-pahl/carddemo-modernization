import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Helpers without the seeded e2e fixtures live in support-core.ts so the smoke run (no seed) can use them.
export * from './support-core';

const here = dirname(fileURLToPath(import.meta.url));

export const fixtures: {
  linkedAccount: string;
  spareLinkedAccount: string;
  unlinkedAccount: string;
  /** Lowest transaction ID overall, of a USER0001 card, and of a card USER0001 cannot see. */
  firstTranId: string;
  userTranId: string;
  otherTranId: string;
} = JSON.parse(
  readFileSync(resolve(here, '../.fixtures.json'), 'utf8'),
);
