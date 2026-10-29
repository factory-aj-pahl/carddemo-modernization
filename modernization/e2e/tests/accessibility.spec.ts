import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { type Page, expect, test } from '@playwright/test';
import { type At, WALKS } from './screen-walks';
import { expectNoSeriousA11yViolations } from './support';

/**
 * Accessibility suite (check ui-accessibility). Owed: one route per BMS map (app/bms and the module bms
 * directories, 21 maps), each in its initial, validation-error, and populated state = 63 route-states,
 * plus one keyboard-only flow per map that presses every F-key of the program's NAV rules.
 *
 * The owed table below is fixed and independent of the results. Where a state cannot exist for a map,
 * the row says why (from the spec or BMS) and names the closest real state, which is checked instead.
 */
type OwedState = 'initial' | 'error' | 'populated';

interface Substitute {
  reason: string;
  closest: string;
}

interface OwedRoute {
  map: string;
  route: string;
  /** A state listed here does not exist for the map; the closest real state is checked instead. */
  substitutes?: Partial<Record<OwedState, Substitute>>;
}

const MENU_POPULATED: Substitute = {
  reason:
    'A menu shows no record data: BUILD-MENU-OPTIONS fills the option lines from the option table on every send, so the first display is already complete (spec BR-COMEN01C/BR-COADM01C, BMS OPTN001-012 are ASKIP).',
  closest: 'The menu redisplayed with a typed option kept after an invalid key (NAV-COMEN01C-03 / NAV-COADM01C-03).',
};

const OWED: OwedRoute[] = [
  {
    map: 'COSGN00',
    route: '/screens/COSGN00C',
    substitutes: {
      populated: {
        reason: 'COSGN00 has only the USERID and PASSWD inputs and no data fields; a successful sign-on transfers to a menu (NAV-COSGN00C-04).',
        closest: 'Both fields typed (password masked) before ENTER.',
      },
    },
  },
  { map: 'COMEN01', route: '/screens/COMEN01C', substitutes: { populated: MENU_POPULATED } },
  { map: 'COADM01', route: '/screens/COADM01C', substitutes: { populated: MENU_POPULATED } },
  { map: 'COACTVW', route: '/screens/COACTVWC' },
  { map: 'COACTUP', route: '/screens/COACTUPC' },
  { map: 'COCRDLI', route: '/screens/COCRDLIC' },
  { map: 'COCRDSL', route: '/screens/COCRDSLC' },
  { map: 'COCRDUP', route: '/screens/COCRDUPC' },
  { map: 'COTRN00', route: '/screens/COTRN00C' },
  { map: 'COTRN01', route: '/screens/COTRN01C' },
  { map: 'COTRN02', route: '/screens/COTRN02C' },
  { map: 'CORPT00', route: '/screens/CORPT00C' },
  { map: 'COBIL00', route: '/screens/COBIL00C' },
  { map: 'COUSR00', route: '/screens/COUSR00C' },
  {
    map: 'COUSR01',
    route: '/screens/COUSR01C',
    substitutes: {
      populated: {
        reason: 'COUSR01C only adds users; it never reads a stored record to display (spec COUSR01C, BMS COUSR01 fields are all input).',
        closest: 'Every input typed before ENTER (the form fully populated by the user).',
      },
    },
  },
  { map: 'COUSR02', route: '/screens/COUSR02C' },
  { map: 'COUSR03', route: '/screens/COUSR03C' },
  { map: 'COPAU00', route: '/screens/COPAUS0C' },
  {
    map: 'COPAU01',
    route: '/screens/COPAUS1C',
    substitutes: {
      error: {
        reason: 'COPAU01 has no unprotected field (every DFHMDF is ASKIP/PROT) and COPAUS1C has no validation rule for user input; the only error shown on a valid entry is the invalid-key message.',
        closest: 'An invalid key (F7) over the shown detail: "Invalid key pressed. Please see below..." (NAV-COPAUS1C-07).',
      },
    },
  },
  { map: 'COTRTLI', route: '/screens/COTRTLIC' },
  { map: 'COTRTUP', route: '/screens/COTRTUPC' },
];

const STATES: OwedState[] = ['initial', 'error', 'populated'];
const STATE_LABEL: Record<OwedState, string> = { initial: 'initial', error: 'validation-error', populated: 'populated' };
const OWED_STATES = OWED.length * STATES.length;

const here = dirname(fileURLToPath(import.meta.url));
// Under the Playwright output directory, which is emptied at the start of every run.
const results = resolve(here, '../test-results');
const ledger = join(results, 'a11y-coverage');

function record(kind: 'state' | 'flow' | 'extra', key: string, detail: object) {
  mkdirSync(ledger, { recursive: true });
  writeFileSync(join(ledger, `${kind}--${key.replace(/[^A-Za-z0-9-]/g, '_')}.json`), JSON.stringify({ kind, key, ...detail }));
}

function recorded(kind: 'state' | 'flow' | 'extra'): Set<string> {
  let files: string[] = [];
  try {
    files = readdirSync(ledger);
  } catch {
    return new Set();
  }
  return new Set(
    files
      .filter((f) => f.startsWith(`${kind}--`))
      .map((f) => (JSON.parse(readFileSync(join(ledger, f), 'utf8')) as { key: string }).key),
  );
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]';
const REACHED = 'data-a11y-reached';

/**
 * Tab once around the whole page and fail if a visible control is never reached or a positive tabindex
 * reorders the sequence. Focus is put back where the walk left it, so the walk continues unchanged.
 */
async function expectTabReachesEveryControl(page: Page) {
  const start = await page.evaluateHandle(() => document.activeElement);
  for (let i = 0; i < 200; i++) {
    await page.keyboard.press('Tab');
    const cycled = await page.evaluate((mark) => {
      const el = document.activeElement;
      if (!el || el === document.body) return false;
      if (el.hasAttribute(mark)) return true;
      el.setAttribute(mark, '');
      return false;
    }, REACHED);
    if (cycled) break;
  }
  const problems = await page.evaluate(
    ([selector, mark]) => {
      const name = (el: Element) =>
        `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''} "${(el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 40)}"`;
      // Tab enters a radio group once; the arrow keys move between its buttons.
      const radioReached = new Set(
        [...document.querySelectorAll(`input[type="radio"][${mark}]`)].map((el) => (el as HTMLInputElement).name),
      );
      const out: string[] = [];
      for (const el of document.querySelectorAll(selector)) {
        const h = el as HTMLElement;
        if (h.tabIndex > 0) out.push(`positive tabindex: ${name(el)}`);
        const shown = h.getClientRects().length > 0 && getComputedStyle(h).visibility !== 'hidden';
        const reached = el.hasAttribute(mark) || (el instanceof HTMLInputElement && el.type === 'radio' && radioReached.has(el.name));
        if (shown && h.tabIndex >= 0 && !reached) out.push(`not reachable with Tab: ${name(el)}`);
      }
      for (const el of document.querySelectorAll(`[${mark}]`)) el.removeAttribute(mark);
      return out;
    },
    [FOCUSABLE, REACHED] as const,
  );
  await start.evaluate((el) => (el instanceof HTMLElement && el !== document.body ? el.focus() : undefined));
  expect(problems).toEqual([]);
}

async function checkState(page: Page) {
  await expectNoSeriousA11yViolations(page);
  await expectTabReachesEveryControl(page);
}

if (WALKS.length !== OWED.length || WALKS.some((w, i) => w.map !== OWED[i].map || `/screens/${w.program}` !== OWED[i].route)) {
  throw new Error('screen walks and the owed table disagree');
}

test.describe('ui-accessibility', () => {
  // The COUSR01C, COUSR02C, and COUSR03C flows add, change, and delete one user in this order.
  test.describe.configure({ mode: 'serial' });

  for (const [i, walk] of WALKS.entries()) {
    const owed = OWED[i];
    const subs = owed.substitutes ?? {};
    const note = Object.keys(subs).length ? ` (closest real state for ${Object.keys(subs).map((s) => STATE_LABEL[s as OwedState]).join(', ')})` : '';

    test(`${walk.map} route-states initial, validation-error, populated pass axe and Tab reach with keyboard-only entry${note}`, async ({ page }) => {
      const seen: OwedState[] = [];
      const at: At = async (state, how) => {
        expect(STATES, `unexpected state ${state} in the ${walk.map} state walk`).toContain(state);
        const s = state as OwedState;
        expect(s, `${walk.map} states out of order`).toBe(STATES[seen.length]);
        await checkState(page);
        seen.push(s);
        record('state', `${walk.map}/${s}`, { route: owed.route, how, substitute: subs[s] ?? null });
      };
      await walk.states(page, at);
      expect(seen).toEqual(STATES);
    });

    test(`${walk.flowIds.join(' ')} ${walk.map} keyboard-only primary flow and F-keys`, async ({ page }) => {
      const at: At = async (state, how) => {
        await checkState(page);
        record('extra', `${walk.map}/${state}`, { route: owed.route, how });
      };
      await walk.flow(page, at);
      record('flow', walk.map, { route: owed.route, ids: walk.flowIds });
    });
  }

  test('every owed route-state and keyboard flow was checked', async () => {
    const states = recorded('state');
    const flows = recorded('flow');
    const extras = recorded('extra');
    const owedKeys = OWED.flatMap((o) => STATES.map((s) => `${o.map}/${s}`));
    const checked = owedKeys.filter((k) => states.has(k));
    const flowed = OWED.filter((o) => flows.has(o.map));
    const line = `a11y route-states checked ${checked.length}/${OWED_STATES}; keyboard flows ${flowed.length}/${OWED.length}; extra states checked ${extras.size}`;
    console.log(line);

    const table = [
      '| Map | Route | State | Exists | Checked | Reason / closest real state |',
      '|:--|:--|:--|:--|:--|:--|',
      ...OWED.flatMap((o) =>
        STATES.map((s) => {
          const sub = o.substitutes?.[s];
          return `| ${o.map} | ${o.route} | ${STATE_LABEL[s]} | ${sub ? 'no' : 'yes'} | ${states.has(`${o.map}/${s}`) ? 'yes' : 'NO'} | ${sub ? `${sub.reason} Checked instead: ${sub.closest}` : ''} |`;
        }),
      ),
      '',
      '| Map | Keyboard flow checked |',
      '|:--|:--|',
      ...OWED.map((o) => `| ${o.map} | ${flows.has(o.map) ? 'yes' : 'NO'} |`),
      '',
      `Extra states checked: ${[...extras].sort().join(', ')}`,
    ];
    writeFileSync(join(results, 'a11y-coverage.txt'), `${line}\n`);
    writeFileSync(join(results, 'a11y-owed.md'), `${line}\n\n${table.join('\n')}\n`);

    expect(owedKeys.filter((k) => !states.has(k)), 'owed route-states not checked').toEqual([]);
    expect(OWED.filter((o) => !flows.has(o.map)).map((o) => o.map), 'keyboard flows not completed').toEqual([]);
  });
});
