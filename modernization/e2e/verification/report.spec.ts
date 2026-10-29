import { type Page, expect, test } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { expectMessage, expectProgram, signOn } from '../tests/support-core';

// Driven by modernization/verification/schedule/verify_report.py against the running stack with the real
// Airflow: REPORT_USER requests a custom-range report on CORPT00C, waits for the report DAG run to complete,
// and downloads the file to REPORT_OUT. REPORT_META receives the run ID and the states seen.
const user = process.env.REPORT_USER ?? 'USER0001';
const [startYear, startMonth, startDay] = (process.env.REPORT_START ?? '').split('-');
const [endYear, endMonth, endDay] = (process.env.REPORT_END ?? '').split('-');
const out = process.env.REPORT_OUT ?? '';
const meta = process.env.REPORT_META ?? '';
const timeoutMs = Number(process.env.REPORT_TIMEOUT_MS ?? 300_000);

const field = (page: Page, date: 'start-date' | 'end-date', part: 'Month' | 'Day' | 'Year') =>
  page.getByTestId(date).getByLabel(part, { exact: true });

test(`schedule-cycle (c): ${user} requests a custom report, the report DAG completes, the file downloads`, async ({ page }) => {
  test.setTimeout(timeoutMs + 60_000);
  expect(out, 'REPORT_OUT').not.toBe('');
  expect(startDay && endDay, 'REPORT_START and REPORT_END (YYYY-MM-DD)').toBeTruthy();

  await signOn(page, user);
  await expectProgram(page, user.startsWith('ADMIN') ? 'CA00 · COADM01C' : 'CM00 · COMEN01C');
  if (user.startsWith('ADMIN')) {
    // A-1: the admin menu has no report option; an admin opens the report screen directly.
    await page.goto('/screens/CORPT00C');
  } else {
    await page.getByLabel('Please select an option').fill('9');
    await page.keyboard.press('Enter');
  }
  await expectProgram(page, 'CR00 · CORPT00C');

  await page.getByLabel(/^Custom/).check();
  for (const [part, s, e] of [['Month', startMonth, endMonth], ['Day', startDay, endDay], ['Year', startYear, endYear]] as const) {
    await field(page, 'start-date', part).fill(s);
    await field(page, 'end-date', part).fill(e);
  }
  await page.keyboard.press('Enter');
  await expectMessage(page, 'Please confirm to print the Custom report...');
  await page.getByLabel(/Please confirm/).fill('Y');
  await page.keyboard.press('Enter');
  await expectMessage(page, 'Custom report submitted for printing ...');

  const run = page.getByTestId('run').first();
  await expect(run).toContainText('Custom');
  await expect(run).toContainText(user);
  const states: string[] = [];
  const deadline = Date.now() + timeoutMs;
  let state = '';
  while (Date.now() < deadline) {
    await page.getByRole('button', { name: 'Refresh status' }).click();
    await page.waitForTimeout(500);
    state = ((await run.getByTestId('run-state').textContent()) ?? '').trim();
    if (states.at(-1) !== state) states.push(state);
    if (state === 'Completed' || state === 'Failed') break;
    await page.waitForTimeout(4_500);
  }
  const link = run.getByRole('link', { name: /Download/ });
  const href = state === 'Completed' ? await link.getAttribute('href') : null;
  const runId = /\/api\/reports\/([^/]+)\//.exec(href ?? '')?.[1] ?? null;
  const record = (extra: object) => {
    if (meta) {
      mkdirSync(dirname(meta), { recursive: true });
      writeFileSync(meta, JSON.stringify({ user, start: process.env.REPORT_START, end: process.env.REPORT_END, runId, states, ...extra }, null, 2));
    }
  };
  record({});
  expect(state, `report run state (seen: ${states.join(' -> ')})`).toBe('Completed');

  const [download] = await Promise.all([page.waitForEvent('download'), link.click()]);
  mkdirSync(dirname(out), { recursive: true });
  await download.saveAs(out);
  record({ download: out, suggestedFilename: download.suggestedFilename() });
});
