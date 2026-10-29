import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { type Page, test } from '@playwright/test';
import { type At, WALKS } from './screen-walks';

/**
 * Screenshots for the UI review; runs only when CAPTURE_DIR is set. Every BMS map is captured in its
 * initial, validation-error, and populated (or closest real) state by the same keyboard-only walks the
 * accessibility suite uses, plus the paging, confirm, and result states its flow passes through. Files
 * are <MAP>-<state>.png (1280x820 viewport, full page); INDEX.md lists file, route, state, and how reached.
 */
const dir = process.env.CAPTURE_DIR;
test.skip(!dir, 'set CAPTURE_DIR to capture screenshots');
const VIEWPORT = { width: 1280, height: 820 };
test.use({ viewport: VIEWPORT });

/**
 * The header and key bar stay put and main scrolls (the 3270 frame), so a plain full-page screenshot
 * stops at 820px. The viewport is grown by main's overflow for the shot, so long lists are seen whole.
 */
async function fullShot(page: Page, path: string) {
  const extra = await page.locator('main').evaluate((m) => m.scrollHeight - m.clientHeight).catch(() => 0);
  if (extra > 0) await page.setViewportSize({ width: VIEWPORT.width, height: VIEWPORT.height + extra });
  await page.screenshot({ path, fullPage: true, animations: 'disabled' });
  if (extra > 0) await page.setViewportSize(VIEWPORT);
}

const here = dirname(fileURLToPath(import.meta.url));
const shots = resolve(here, '../test-results/capture-index');

interface Shot {
  file: string;
  map: string;
  route: string;
  state: string;
  how: string;
  order: number;
}

test.describe('ui-review capture', () => {
  // The user add, change, and delete walks depend on each other, in WALKS order.
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(() => {
    rmSync(shots, { recursive: true, force: true });
    mkdirSync(shots, { recursive: true });
    mkdirSync(dir!, { recursive: true });
  });

  for (const [i, walk] of WALKS.entries()) {
    test(`capture ${walk.map}`, async ({ page }) => {
      // A shot must show the settled state, never a border still fading from the previous turn's error.
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.addInitScript(() => {
        const css = '*,*::before,*::after{transition:none!important;animation:none!important}';
        const add = () => document.head.appendChild(Object.assign(document.createElement('style'), { textContent: css }));
        if (document.head) add();
        else document.addEventListener('DOMContentLoaded', add);
      });
      const taken = new Set<string>();
      let order = 0;
      const at: At = async (state, how) => {
        const file = `${walk.map}-${state}.png`;
        if (taken.has(file)) throw new Error(`${file} captured twice`);
        taken.add(file);
        await fullShot(page, join(dir!, file));
        const shot: Shot = { file, map: walk.map, route: new URL(page.url()).pathname, state, how, order: i * 100 + order++ };
        writeFileSync(join(shots, `${file}.json`), JSON.stringify(shot));
      };
      await walk.states(page, at);
      await walk.flow(page, at);
    });
  }

  test('write INDEX.md', () => {
    const all = readdirSync(shots)
      .map((f) => JSON.parse(readFileSync(join(shots, f), 'utf8')) as Shot)
      .sort((a, b) => a.order - b.order);
    const missing = WALKS.flatMap((w) => ['initial', 'error', 'populated'].map((s) => `${w.map}-${s}.png`)).filter(
      (f) => !all.some((s) => s.file === f),
    );
    const cell = (s: string) => s.replace(/\|/g, '\\|');
    const lines = [
      '# UI review screenshots',
      '',
      `${all.length} screenshots of ${WALKS.length} BMS maps, reached with the keyboard only (modernization/e2e/tests/capture.spec.ts with CAPTURE_DIR set). Viewport 1280x820, full page, CSS transitions and animations disabled (reduced motion) so every shot shows the settled state; when the main area scrolls (long lists), the viewport is made taller by that overflow so the whole screen is in the shot.`,
      '',
      '| File | Route | State | How reached |',
      '|:--|:--|:--|:--|',
      ...all.map((s) => `| [${s.file}](${s.file}) | ${s.route} | ${s.state} | ${cell(s.how)} |`),
      '',
    ];
    writeFileSync(join(dir!, 'INDEX.md'), lines.join('\n'));
    if (missing.length) throw new Error(`missing screenshots: ${missing.join(', ')}`);
  });
});
