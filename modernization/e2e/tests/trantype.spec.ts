import { type Page, expect, test } from '@playwright/test';
import { expectMessage, expectNoSeriousA11yViolations, expectProgram, signOn } from './support';

const LIST = 'CTLI · COTRTLIC';
const MAINT = 'CTTU · COTRTUPC';

async function adminOption(page: Page, name: RegExp, program: string) {
  await signOn(page, 'ADMIN001');
  await expectProgram(page, 'CA00 · COADM01C');
  await page.getByRole('button', { name }).click();
  await expectProgram(page, program);
}

async function expectInfo(page: Page, text: string) {
  await expect(page.getByTestId('info')).toHaveText(text);
}

function legend(page: Page) {
  return page.getByRole('navigation', { name: 'Function keys' }).locator('kbd');
}

test.describe('COTRTLIC transaction type list', () => {
  test.beforeEach(async ({ page }) => {
    await adminOption(page, /Transaction Type List\/Update/, LIST);
  });

  test('TS-COTRTLIC-01 first entry lists page 1 of the types with focus on the type filter', async ({ page }) => {
    await expect(page.getByTestId('page')).toHaveText('1');
    for (const [n, code] of ['01', '02', '03', '04', '05', '06', '07'].entries()) {
      await expect(page.getByTestId(`row${n + 1}`).locator('td').nth(1)).toHaveText(code);
    }
    await expectInfo(page, 'Type U to update, D to delete any record');
    await expect(page.getByLabel('Type Filter')).toBeFocused();
    await expect(legend(page)).toHaveText(['F2', 'F3', 'F7', 'F8', 'F10']);
    await expectNoSeriousA11yViolations(page);
  });

  test('VR-COTRTLIC-01 a type filter that is not a 2 digit number is rejected', async ({ page }) => {
    await page.getByLabel('Type Filter').fill('AB');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER');
    await expect(page.getByLabel('Type Filter')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Type Filter')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COTRTLIC-10 keyboard only: mark a row with U, change its description, save with F10', async ({ page }) => {
    await expect(page.getByLabel('Type Filter')).toBeFocused();
    for (let i = 0; i < 4; i++) await page.keyboard.press('Tab');
    await expect(page.getByLabel('Select row 2')).toBeFocused();
    await page.keyboard.type('u');
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Update HIGHLIGHTED row. Press F10 to save');
    const desc = page.getByLabel('Description row 2');
    await expect(desc).toBeFocused();
    await expect(page.getByLabel('Type Filter')).toHaveAttribute('readonly', '');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.type('Payment received');
    await page.keyboard.press('F10');
    await expectInfo(page, 'HIGHLIGHTED row was updated');
    await expect(page.getByTestId('row2').locator('td').nth(2)).toHaveText('Payment received');
    await expectNoSeriousA11yViolations(page);
  });

  test('Q-COTRTLIC-19 a row another session changed meanwhile is rejected and shown with the re-read value', async ({ page, browser, baseURL }) => {
    await page.getByLabel('Select row 4').fill('U');
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Update HIGHLIGHTED row. Press F10 to save');

    const other = await browser.newContext({ baseURL });
    try {
      const page2 = await other.newPage();
      await adminOption(page2, /Transaction Type List\/Update/, LIST);
      await page2.getByLabel('Select row 4').fill('U');
      await page2.keyboard.press('Enter');
      await page2.getByLabel('Description row 4').fill('Changed Elsewhere');
      await page2.keyboard.press('F10');
      await expectInfo(page2, 'HIGHLIGHTED row was updated');
    } finally {
      await other.close();
    }

    const desc = page.getByLabel('Description row 4');
    await desc.fill('Typed Here');
    await page.keyboard.press('F10');
    await expectMessage(page, 'Deadlock. Someone else updating ?');
    await expect(desc).toHaveValue('Changed Elsewhere');
    await expect(page.getByLabel('Select row 4')).toHaveValue('U');
    await expectNoSeriousA11yViolations(page);
    // F10 again saves only what is shown, so the other session's value stays.
    await page.keyboard.press('F10');
    await expectInfo(page, 'HIGHLIGHTED row was updated');
    await expect(page.getByTestId('row4').locator('td').nth(2)).toHaveText('Changed Elsewhere');
  });

  test('NAV-COTRTLIC-01 F3 returns to the admin menu', async ({ page }) => {
    await page.keyboard.press('F3');
    await expectProgram(page, 'CA00 · COADM01C');
  });
});

test.describe('COTRTUPC transaction type maintenance', () => {
  test.beforeEach(async ({ page }) => {
    await adminOption(page, /Transaction Type Maintenance/, MAINT);
  });

  test('TS-COTRTUPC-01 first entry shows the empty prompt with only ENTER and F3', async ({ page }) => {
    await expectInfo(page, 'Enter transaction type to be maintained');
    await expect(page.getByLabel('Transaction Type')).toBeFocused();
    await expect(page.getByLabel('Transaction Type')).toHaveValue('');
    await expect(page.getByLabel('Description')).toHaveAttribute('readonly', '');
    await expect(legend(page)).toHaveText(['ENTER', 'F3']);
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COTRTUPC-04 a type that is not numeric is rejected and marked', async ({ page }) => {
    await page.getByLabel('Transaction Type').fill('A1');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Tran Type code must be numeric.');
    await expect(page.getByLabel('Transaction Type')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Transaction Type')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COTRTUPC-07 TS-COTRTUPC-14 keyboard only: add a type, then delete it', async ({ page }) => {
    await page.keyboard.type('41');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'No record found for this key in database');
    await expectInfo(page, 'Press F05 to add. F12 to cancel');
    await expect(legend(page)).toHaveText(['ENTER', 'F3', 'F5', 'F12']);
    await page.keyboard.press('F5');
    await expectInfo(page, 'Enter new transaction type details.');
    await expect(page.getByLabel('Description')).toBeFocused();
    await page.keyboard.type('Interest');
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Changes validated.Press F5 to save');
    await page.keyboard.press('F5');
    await expectInfo(page, 'Changes committed to database');
    await expect(page.getByLabel('Transaction Type')).toHaveValue('41');
    await expect(page.getByLabel('Description')).toHaveValue('Interest');
    await expectNoSeriousA11yViolations(page);

    await page.keyboard.press('Enter');
    await expectInfo(page, 'Enter transaction type to be maintained');
    await expect(page.getByLabel('Transaction Type')).toBeFocused();
    await page.keyboard.type('41');
    await page.keyboard.press('Enter');
    await expect(page.getByLabel('Description')).toHaveValue('Interest');
    await expectInfo(page, 'Update transaction type details shown.');
    await page.keyboard.press('F4');
    await expectInfo(page, 'Delete this record ? Press F4 to confirm');
    await expect(legend(page)).toHaveText(['F4', 'F12']);
    await page.keyboard.press('F4');
    await expectInfo(page, 'Delete successful.');
    await expect(page.getByLabel('Transaction Type')).toHaveValue('');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COTRTUPC-18 F2 on the list opens maintenance and F3 returns to the list', async ({ page }) => {
    await page.keyboard.press('F3');
    await expectProgram(page, 'CA00 · COADM01C');
    await page.getByRole('button', { name: /Transaction Type List\/Update/ }).click();
    await expectProgram(page, LIST);
    await page.keyboard.press('F2');
    await expectProgram(page, MAINT);
    await page.keyboard.press('F3');
    await expectProgram(page, LIST);
    await expect(page.getByTestId('page')).toHaveText('1');
  });
});
