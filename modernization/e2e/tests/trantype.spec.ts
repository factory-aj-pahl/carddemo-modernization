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

  test('Q-COTRTLIC-23 TS-COTRTLIC-24 a D marked with F7 is confirmed by F10 only after its delete prompt was shown', async ({ page }) => {
    await page.getByLabel('Select row 6').fill('D');
    await page.keyboard.press('F7');
    await expectMessage(page, 'No previous pages to display');
    await expect(page.getByTestId('info')).toHaveCount(0);
    await expect(page.getByLabel('Select row 6')).toHaveValue('D');
    // Without the prompt F10 is ENTER: the delete prompt is shown and nothing is deleted.
    await page.keyboard.press('F10');
    await expectInfo(page, 'Delete HIGHLIGHTED row ? Press F10 to confirm');
    await expect(page.getByTestId('message')).toHaveText('');
    await expect(page.getByTestId('row6').locator('td').nth(1)).toHaveText('06');
    await expect(page.getByTestId('row6')).toHaveAttribute('aria-current', 'true');
    await expectNoSeriousA11yViolations(page);
  });

  test('Q-COTRTLIC-24 TS-COTRTLIC-26 TS-COTRTLIC-27 a type filter typed with F8 or F7 shows page 1 of the new search without a boundary message', async ({ page }) => {
    await page.getByLabel('Type Filter').fill('03');
    await page.keyboard.press('F8');
    await expect(page.getByTestId('row1').locator('td').nth(1)).toHaveText('03');
    await expect(page.getByTestId('row2').locator('td').nth(1)).toHaveText('');
    await expect(page.getByTestId('page')).toHaveText('1');
    await expectInfo(page, 'Type U to update, D to delete any record');
    await expect(page.getByTestId('message')).toHaveText('');
    await page.getByLabel('Type Filter').fill('05');
    await page.keyboard.press('F7');
    await expect(page.getByTestId('row1').locator('td').nth(1)).toHaveText('05');
    await expect(page.getByTestId('page')).toHaveText('1');
    await expectInfo(page, 'Type U to update, D to delete any record');
    await expect(page.getByTestId('message')).toHaveText('');
    await expectNoSeriousA11yViolations(page);
    // The new search is now shown: F8 at its end gives the boundary message.
    await page.keyboard.press('F8');
    await expectMessage(page, 'No more pages for these search conditions');
    // TS-COTRTLIC-27: a description filter R typed with F8. The filter is case-sensitive and the e2e seed has
    // mixed-case descriptions, so only Refund and Reversal match (01, 03, 04, 05, 06 on the upper-case sample).
    await page.getByLabel('Type Filter').fill('');
    await page.getByLabel('Description Filter').fill('R');
    await page.keyboard.press('F8');
    for (const [n, code] of ['05', '06', ''].entries()) {
      await expect(page.getByTestId(`row${n + 1}`).locator('td').nth(1)).toHaveText(code);
    }
    await expect(page.getByTestId('page')).toHaveText('1');
    await expect(page.getByTestId('message')).toHaveText('');
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

  test('TS-COTRTUPC-16 NAV-COTRTUPC-01 ENTER in the delete confirmation is refused and the confirmation is shown again', async ({ page }) => {
    const turns: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().endsWith('/api/screens/COTRTUPC')) turns.push(JSON.parse(r.postData() ?? '{}').key);
    });
    await page.keyboard.type('01');
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Update transaction type details shown.');
    const description = await page.getByLabel('Description').inputValue();
    await page.keyboard.press('F4');
    await expectInfo(page, 'Delete this record ? Press F4 to confirm');
    await expect(legend(page)).toHaveText(['F4', 'F12']);
    await expect(page.getByRole('button', { name: /Process/ })).toHaveCount(0);

    // ENTER is dark in state 9 but still an AID key: from wherever the cursor is, it reaches the program.
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Invalid key pressed');
    await expectInfo(page, 'Delete this record ? Press F4 to confirm');
    await expect(legend(page)).toHaveText(['F4', 'F12']);
    await page.getByLabel('Description').focus();
    await page.keyboard.press('Enter');
    await expect.poll(() => turns.filter((k) => k === 'ENTER').length).toBe(3);
    await expectMessage(page, 'Invalid key pressed');
    await expectInfo(page, 'Delete this record ? Press F4 to confirm');
    await expectNoSeriousA11yViolations(page);

    await page.keyboard.press('F12');
    await expectMessage(page, 'Delete was cancelled');
    await expect(page.getByLabel('Transaction Type')).toBeFocused();
    await page.keyboard.type('01');
    await page.keyboard.press('Enter');
    await expect(page.getByLabel('Description')).toHaveValue(description);
  });

  test('ENTER from the page background, outside any field, is the ENTER key', async ({ page }) => {
    await page.getByLabel('Transaction Type').fill('A1');
    await page.locator('main').focus();
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Tran Type code must be numeric.');
    await expect(page.getByLabel('Transaction Type')).toBeFocused();
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
