import { type Page, expect, test } from '@playwright/test';
import { MSG, expectMessage, expectNoSeriousA11yViolations, expectProgram, open, signOn } from './support';

const field = (page: Page, date: 'start-date' | 'end-date', part: 'Month' | 'Day' | 'Year') =>
  page.getByTestId(date).getByLabel(part, { exact: true });

async function custom(page: Page, start: [string, string, string], end: [string, string, string]) {
  await page.getByLabel(/^Custom/).check();
  for (const [i, part] of (['Month', 'Day', 'Year'] as const).entries()) {
    await field(page, 'start-date', part).fill(start[i]);
    await field(page, 'end-date', part).fill(end[i]);
  }
}

test.describe('CORPT00C transaction reports', () => {
  test.beforeEach(async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
  });

  test('NAV-CORPT00C-02 menu option 9 shows the empty request form', async ({ page }) => {
    await page.getByLabel('Please select an option').fill('9');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CR00 · CORPT00C');
    await expect(page.getByLabel(/^Monthly/)).toBeFocused();
    for (const t of [/^Monthly/, /^Yearly/, /^Custom/]) await expect(page.getByLabel(t)).not.toBeChecked();
    await expect(page.getByLabel(/Please confirm/)).toHaveValue('');
    await expect(page.getByTestId('message')).toHaveText('');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-CORPT00C-12 TS-CORPT00C-06 TS-CORPT00C-07 TS-CORPT00C-08 VR-CORPT00C-01 VR-CORPT00C-02 VR-CORPT00C-08 VR-CORPT00C-14 the type and custom dates are validated', async ({ page }) => {
    await open(page, '/screens/CORPT00C', 'CR00 · CORPT00C');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Select a report type to print report...');
    await expect(page.getByLabel(/^Monthly/)).toBeFocused();

    await custom(page, ['', '01', '2022'], ['06', '30', '2022']);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Start Date - Month can NOT be empty...');
    await expect(field(page, 'start-date', 'Month')).toHaveAttribute('aria-invalid', 'true');
    await expect(field(page, 'start-date', 'Month')).toBeFocused();
    await expectNoSeriousA11yViolations(page);

    await field(page, 'start-date', 'Month').fill('13');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Start Date - Not a valid Month...');

    await custom(page, ['02', '30', '2022'], ['06', '30', '2022']);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Start Date - Not a valid date...');
    await expect(field(page, 'start-date', 'Month')).toBeFocused();
  });

  test('TS-CORPT00C-05 TS-CORPT00C-09 BR-CORPT00C-04 a custom range is normalized and asks for confirmation', async ({ page }) => {
    await open(page, '/screens/CORPT00C', 'CR00 · CORPT00C');
    await custom(page, ['6', '1', '2022'], ['06', '30', '2022']);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Please confirm to print the Custom report...');
    await expect(field(page, 'start-date', 'Month')).toHaveValue('06');
    await expect(field(page, 'start-date', 'Day')).toHaveValue('01');
    await expect(page.getByLabel(/Please confirm/)).toBeFocused();
  });

  test('TS-CORPT00C-13 VR-CORPT00C-17 a confirmation other than Y or N is rejected', async ({ page }) => {
    await open(page, '/screens/CORPT00C', 'CR00 · CORPT00C');
    await page.getByLabel(/^Monthly/).check();
    await page.getByLabel(/Please confirm/).fill('X');
    await page.keyboard.press('Enter');
    await expectMessage(page, '"X" is not a valid value to confirm...');
    await expect(page.getByLabel(/Please confirm/)).toHaveAttribute('aria-invalid', 'true');
  });

  test('TS-CORPT00C-01 TS-CORPT00C-02 VR-CORPT00C-16 keyboard only: a confirmed monthly report runs and can be downloaded', async ({ page }) => {
    await open(page, '/screens/CORPT00C', 'CR00 · CORPT00C');
    const monthly = page.getByLabel(/^Monthly/);
    const confirm = page.getByLabel(/Please confirm/);
    await expect(monthly).toBeFocused();
    await page.keyboard.press('Space');
    await expect(monthly).toBeChecked();
    for (let i = 0; i < 10 && !(await confirm.evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('Tab');
    await expect(confirm).toBeFocused();
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Please confirm to print the Monthly report...');
    await expect(confirm).toBeFocused();
    await page.keyboard.type('y');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Monthly report submitted for printing ...');
    await expect(page.getByTestId('message')).toHaveClass(/success/);
    await expect(monthly).not.toBeChecked();

    const run = page.getByTestId('run').first();
    await expect(run).toContainText('Monthly');
    await expect(run).toContainText('USER0001');
    await page.getByRole('button', { name: 'Refresh status' }).click();
    await expect(run.getByTestId('run-state')).toHaveText('Completed');
    const link = run.getByRole('link', { name: /Download/ });
    const href = await link.getAttribute('href');
    expect(href).toMatch(/^\/api\/reports\/CR00_USER0001_.+\/TRANREPT\.txt$/);
    const report = await page.request.get(href ?? '');
    expect(report.status()).toBe(200);
    expect(await report.text()).toContain('Requested by USER0001');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-CORPT00C-14 NAV-CORPT00C-04 NAV-CORPT00C-05 other keys are invalid and F3 returns to the menu', async ({ page }) => {
    await open(page, '/screens/CORPT00C', 'CR00 · CORPT00C');
    await page.keyboard.press('F4');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });
});
