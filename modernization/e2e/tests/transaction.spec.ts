import { type Page, expect, test } from '@playwright/test';
import { DEMO_PASSWORD, MSG, expectMessage, expectNoSeriousA11yViolations, expectProgram, fixtures, open, signOn } from './support';

const rows = (page: Page) => page.getByTestId('row');
const firstRowId = (page: Page) => rows(page).first().locator('td').nth(1);

async function menuOption(page: Page, option: string, tranAndProgram: string) {
  await page.getByLabel('Please select an option').fill(option);
  await page.keyboard.press('Enter');
  await expectProgram(page, tranAndProgram);
}

test.describe('COTRN00C transaction list', () => {
  test.beforeEach(async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
  });

  test('TS-COTRN00C-01 NAV-COTRN00C-02 C-SCOPE menu option 6 shows page 1 of the user transactions', async ({ page }) => {
    await menuOption(page, '6', 'CT00 · COTRN00C');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
    await expect(rows(page)).toHaveCount(10);
    await expect(firstRowId(page)).toHaveText(fixtures.userTranId);
    await expect(rows(page).first().locator('td').nth(2)).toHaveText(/^\d\d\/\d\d\/\d\d$/);
    // TAMT0nn is edited as +99999999.99 and PAGENUM shows 8 digits.
    await expect(rows(page).first().locator('td').nth(4)).toHaveText(/^[+-]\d{8}\.\d{2}$/);
    await expect(page.getByTestId('page')).toHaveText('Page 00000001');
    await expect(page.getByLabel('Search Tran ID')).toBeFocused();
    await expect(page.getByTestId('message')).toHaveText('');
    await expectNoSeriousA11yViolations(page);
  });

  test('VR-COTRN00C-02 TS-COTRN00C-07 a non-numeric search ID is rejected and the page stays', async ({ page }) => {
    await open(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
    await page.getByLabel('Search Tran ID').fill('ABC');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Tran ID must be Numeric ...');
    await expect(page.getByLabel('Search Tran ID')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Search Tran ID')).toBeFocused();
    await expect(rows(page)).toHaveCount(10);
    await expectNoSeriousA11yViolations(page);
  });

  test('Q-COTRN00C-02 a typed search ID beyond the last transaction is not found and the field is marked', async ({ page }) => {
    await open(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
    await page.getByLabel('Search Tran ID').fill('9999999999999999');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Transaction ID NOT found...');
    await expect(rows(page)).toHaveCount(0);
    await expect(page.getByLabel('Search Tran ID')).toBeFocused();
    await expect(page.getByLabel('Search Tran ID')).toHaveAttribute('aria-invalid', 'true');
  });

  test('TS-COTRN00C-06 NAV-COTRN00C-03 a search ID starts the list at that transaction', async ({ page }) => {
    await open(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
    const third = await rows(page).nth(2).locator('td').nth(1).textContent();
    await page.getByLabel('Search Tran ID').fill(third ?? '');
    await page.keyboard.press('Enter');
    await expect(firstRowId(page)).toHaveText(third ?? '');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
  });

  test('VR-COTRN00C-01 TS-COTRN00C-09 a selection other than S is rejected', async ({ page }) => {
    await open(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
    await page.getByLabel(`Select transaction ${fixtures.userTranId}`).fill('X');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Invalid selection. Valid value is S');
    await expect(firstRowId(page)).toHaveText(fixtures.userTranId);
  });

  test('TS-COTRN00C-02 TS-COTRN00C-03 TS-COTRN00C-04 TS-COTRN00C-05 NAV-COTRN00C-05 NAV-COTRN00C-06 F7 and F8 page through the list', async ({ page }) => {
    await open(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
    await page.keyboard.press('F7');
    await expectMessage(page, 'You are already at the top of the page...');
    await page.keyboard.press('F8');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000002');
    await expect(firstRowId(page)).not.toHaveText(fixtures.userTranId);
    await page.keyboard.press('F7');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
    await expectMessage(page, 'You have reached the top of the page...');
    await expect(firstRowId(page)).toHaveText(fixtures.userTranId);
    const message = () => page.getByTestId('message').textContent();
    for (let i = 0; i < 12 && !(await message())?.includes('You have reached the bottom of the page...'); i++) {
      const before = await page.getByTestId('page').getAttribute('data-raw');
      await page.keyboard.press('F8');
      await expect(page.getByTestId('page')).not.toHaveAttribute('data-raw', before ?? '');
    }
    await expectMessage(page, 'You have reached the bottom of the page...');
    await page.keyboard.press('F8');
    await expectMessage(page, 'You are already at the bottom of the page...');
  });

  test('TS-COTRN00C-13 Q-COTRN00C-13 BR-COTRN00C-08 F7 on a page 1 that is also the last page does not open a next page', async ({ page }) => {
    await open(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
    const message = () => page.getByTestId('message').textContent();
    for (let i = 0; i < 12 && !(await message())?.includes('You have reached the bottom of the page...'); i++) {
      const before = await page.getByTestId('page').getAttribute('data-raw');
      await page.keyboard.press('F8');
      await expect(page.getByTestId('page')).not.toHaveAttribute('data-raw', before ?? '');
    }
    const lastPageFirst = (await firstRowId(page).textContent()) ?? '';
    await page.getByLabel('Search Tran ID').fill(lastPageFirst);
    await page.keyboard.press('Enter');
    await expect(firstRowId(page)).toHaveText(lastPageFirst);
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
    await expectMessage(page, 'You have reached the bottom of the page...');
    const shown = await rows(page).locator('td:nth-child(2)').allTextContents();
    await page.keyboard.press('F7');
    await expectMessage(page, 'You are already at the top of the page...');
    await page.keyboard.press('F8');
    await expectMessage(page, 'You are already at the bottom of the page...');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
    await expect(rows(page).locator('td:nth-child(2)')).toHaveText(shown);
  });

  test('TS-COTRN00C-08 NAV-COTRN00C-08 TS-COTRN01C-01 TS-COTRN01C-07 keyboard only: S opens the view and F3 returns to the list', async ({ page }) => {
    await open(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
    await expect(page.getByLabel('Search Tran ID')).toBeFocused();
    const sel = page.getByLabel(`Select transaction ${fixtures.userTranId}`);
    for (let i = 0; i < 4 && !(await sel.evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('Tab');
    await expect(sel).toBeFocused();
    await page.keyboard.type('s');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CT01 · COTRN01C');
    await expect(page.getByTestId('tranId')).toHaveText(fixtures.userTranId);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CT00 · COTRN00C');
    await expect(firstRowId(page)).toHaveText(fixtures.userTranId);
  });

  test('TS-COTRN00C-12 NAV-COTRN00C-04 NAV-COTRN00C-07 other keys are invalid and F3 returns to the menu', async ({ page }) => {
    await open(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
    await page.keyboard.press('F10');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(rows(page)).toHaveCount(10);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });
});

test.describe('COTRN00C for a user with no transactions in scope', () => {
  // A regular user added through COUSR01C has no linked accounts, so no transaction is in scope.
  const NO_TRAN_USER = 'E2ENOTX1';
  const NO_TRAN_PASSWORD = DEMO_PASSWORD;

  async function deleteIfPresent(page: Page) {
    await signOn(page, 'ADMIN001');
    await expectProgram(page, 'CA00 · COADM01C');
    await open(page, '/screens/COUSR03C', 'CU03 · COUSR03C');
    await page.getByLabel('Enter User ID').fill(NO_TRAN_USER);
    await page.keyboard.press('Enter');
    const prompt = 'Press PF5 key to delete this user ...';
    await expect.poll(() => page.getByTestId('message').locator('.msg-text').textContent()).toMatch(/^(Press PF5 key to delete this user|User ID NOT found)/);
    if ((await page.getByTestId('message').locator('.msg-text').textContent()) === prompt) {
      await page.keyboard.press('F5');
      await expectMessage(page, `User ${NO_TRAN_USER} has been deleted ...`);
    }
  }

  test.beforeEach(async ({ page }) => {
    await deleteIfPresent(page);
    await open(page, '/screens/COUSR01C', 'CU01 · COUSR01C');
    await page.getByLabel('First Name').fill('NO');
    await page.getByLabel('Last Name').fill('TRANSACTIONS');
    await page.getByLabel('User ID').fill(NO_TRAN_USER);
    await page.getByLabel('Password').fill(NO_TRAN_PASSWORD);
    await page.getByLabel('User Type').fill('U');
    await page.keyboard.press('Enter');
    await expectMessage(page, `User ${NO_TRAN_USER} has been added ...`);
    await signOn(page, NO_TRAN_USER, NO_TRAN_PASSWORD);
    await expectProgram(page, 'CM00 · COMEN01C');
  });

  test.afterEach(async ({ page }) => {
    await deleteIfPresent(page);
  });

  test('Q-COTRN00C-02 an empty scope gives "Transaction ID NOT found..." without marking the blank search field', async ({ page }) => {
    const search = page.getByLabel('Search Tran ID');
    await menuOption(page, '6', 'CT00 · COTRN00C');
    await expectMessage(page, 'Transaction ID NOT found...');
    await expect(rows(page)).toHaveCount(0);
    await expect(search).toBeFocused();
    await expect(search).toHaveValue('');
    await expect(search).not.toHaveAttribute('aria-invalid', 'true');
    await expectNoSeriousA11yViolations(page);

    // ENTER with the search still blank: the same empty page, still not about the field.
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Transaction ID NOT found...');
    await expect(search).toBeFocused();
    await expect(search).not.toHaveAttribute('aria-invalid', 'true');

    // A typed ID that finds nothing: the message is about that ID, so the field is marked.
    await search.fill('0000000000000001');
    await page.keyboard.press('Enter');
    await expect(search).toHaveAttribute('aria-invalid', 'true');
    await expectMessage(page, 'Transaction ID NOT found...');
    await expect(search).toBeFocused();
  });
});

test.describe('COTRN01C transaction view', () => {
  test.beforeEach(async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
  });

  test('TS-COTRN01C-02 NAV-COTRN01C-02 menu option 7 shows the empty form', async ({ page }) => {
    await menuOption(page, '7', 'CT01 · COTRN01C');
    await expect(page.getByLabel('Enter Tran ID')).toBeFocused();
    await expect(page.getByLabel('Enter Tran ID')).toHaveValue('');
    await expect(page.getByTestId('tranId')).toHaveText('—');
    await expect(page.getByTestId('message')).toHaveText('');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COTRN01C-04 VR-COTRN01C-01 a blank transaction ID is rejected', async ({ page }) => {
    await open(page, '/screens/COTRN01C', 'CT01 · COTRN01C');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Tran ID can NOT be empty...');
    await expect(page.getByLabel('Enter Tran ID')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Enter Tran ID')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COTRN01C-03 TS-COTRN01C-05 VR-COTRN01C-02 NAV-COTRN01C-03 C-MASK a transaction is shown with the card masked; an unknown ID clears it', async ({ page }) => {
    await open(page, '/screens/COTRN01C', 'CT01 · COTRN01C');
    await page.getByLabel('Enter Tran ID').fill(fixtures.userTranId);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('tranId')).toHaveText(fixtures.userTranId);
    await expect(page.getByTestId('cardNum')).toHaveText(/^\*{12}\d{4}$/);
    await expect(page.getByTestId('amount')).toHaveText(/^[+-]\d{8}\.\d{2}$/);
    await expect(page.getByTestId('message')).toHaveText('');
    await expectNoSeriousA11yViolations(page);
    await page.getByLabel('Enter Tran ID').fill('9999999999999999');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Transaction ID NOT found...');
    await expect(page.getByTestId('tranId')).toHaveText('—');
  });

  test('C-SCOPE a transaction on a card of another user is not found', async ({ page }) => {
    await open(page, '/screens/COTRN01C', 'CT01 · COTRN01C');
    await page.getByLabel('Enter Tran ID').fill(fixtures.otherTranId);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Transaction ID NOT found...');
    await expect(page.getByTestId('cardNum')).toHaveText('—');
  });

  test('TS-COTRN01C-08 TS-COTRN01C-10 NAV-COTRN01C-04 NAV-COTRN01C-05 NAV-COTRN01C-07 keyboard only: F4 clears, other keys are invalid, F3 returns to the menu', async ({ page }) => {
    await menuOption(page, '7', 'CT01 · COTRN01C');
    await page.keyboard.type(fixtures.userTranId);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('tranId')).toHaveText(fixtures.userTranId);
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter Tran ID')).toHaveValue('');
    await expect(page.getByTestId('tranId')).toHaveText('—');
    await page.keyboard.press('F7');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });

  test('TS-COTRN01C-09 NAV-COTRN01C-06 F5 opens the transaction list at page 1', async ({ page }) => {
    await open(page, '/screens/COTRN01C', 'CT01 · COTRN01C');
    await page.keyboard.press('F5');
    await expectProgram(page, 'CT00 · COTRN00C');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
  });
});

test.describe('COTRN02C add transaction', () => {
  test.beforeEach(async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
  });

  const sample: [string, string][] = [
    ['Type CD', '01'],
    ['Category CD', '0001'],
    ['Source', 'POS TERM'],
    ['Description', 'Playwright purchase'],
    ['Amount', '+00000012.34'],
    ['Orig Date', '2024-01-15'],
    ['Proc Date', '2024-01-16'],
    ['Merchant ID', '000000001'],
    ['Merchant Name', 'Playwright Store'],
    ['Merchant City', 'Seattle'],
    ['Merchant Zip', '98101'],
  ];
  const fillSample = async (page: Page) => {
    for (const [label, value] of sample) await page.getByLabel(label, { exact: true }).fill(value);
  };

  test('TS-COTRN02C-04 NAV-COTRN02C-02 menu option 8 shows the empty form; no key is rejected', async ({ page }) => {
    await menuOption(page, '8', 'CT02 · COTRN02C');
    await expect(page.getByLabel('Enter Acct #')).toBeFocused();
    await expect(page.getByLabel('Enter Acct #')).toHaveValue('');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Account or Card Number must be entered...');
    await expect(page.getByLabel('Enter Acct #')).toHaveAttribute('aria-invalid', 'true');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COTRN02C-05 TS-COTRN02C-06 VR-COTRN02C-01 VR-COTRN02C-02 VR-COTRN02C-04 C-SCOPE malformed, unknown, and unlinked keys are rejected', async ({ page }) => {
    await open(page, '/screens/COTRN02C', 'CT02 · COTRN02C');
    await page.getByLabel('Enter Acct #').fill('ABC');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Account ID must be Numeric...');
    await page.getByLabel('Enter Acct #').fill('99999999999');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Account ID NOT found...');
    await page.getByLabel('Enter Acct #').fill(fixtures.unlinkedAccount);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Account ID NOT found...');
    await page.getByLabel('Enter Acct #').fill('');
    await page.getByLabel('Card #').fill('9999999999999999');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Card Number NOT found...');
    await expect(page.getByLabel('Card #')).toHaveAttribute('aria-invalid', 'true');
  });

  test('TS-COTRN02C-07 TS-COTRN02C-08 VR-COTRN02C-06 VR-COTRN02C-17 the data fields are checked in screen order', async ({ page }) => {
    await open(page, '/screens/COTRN02C', 'CT02 · COTRN02C');
    await page.getByLabel('Enter Acct #').fill(fixtures.linkedAccount);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Type CD can NOT be empty...');
    await expect(page.getByLabel('Type CD')).toBeFocused();
    await expect(page.getByLabel('Card #')).toHaveValue(/\d{4}$/);
    await fillSample(page);
    await page.getByLabel('Type CD').fill('1');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Type CD must be Numeric...');
    await expect(page.getByLabel('Type CD')).toHaveAttribute('aria-invalid', 'true');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COTRN02C-01 TS-COTRN02C-02 TS-COTRN02C-11 VR-COTRN02C-25 VR-COTRN02C-26 confirming Y adds the transaction', async ({ page }) => {
    await open(page, '/screens/COTRN02C', 'CT02 · COTRN02C');
    await page.getByLabel('Enter Acct #').fill(fixtures.linkedAccount);
    await fillSample(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Confirm to add this transaction...');
    const confirm = page.getByLabel(/Please confirm/);
    await expect(confirm).toBeFocused();
    await expectNoSeriousA11yViolations(page);
    await confirm.fill('X');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Invalid value. Valid values are (Y/N)...');
    await confirm.fill('Y');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('message').locator('.msg-text')).toHaveText(/^Transaction added successfully\. {2}Your Tran ID is \d{16}\.$/);
    await expect(page.getByTestId('message')).toHaveClass(/success/);
    await expect(page.getByLabel('Enter Acct #')).toHaveValue('');
    await expect(page.getByLabel('Description')).toHaveValue('');

    // The stored record carries the entered fields (seen through the view screen).
    const tranId = /(\d{16})/.exec((await page.getByTestId('message').locator('.msg-text').textContent()) ?? '')![1];
    await open(page, '/screens/COTRN01C', 'CT01 · COTRN01C');
    await page.getByLabel('Enter Tran ID').fill(tranId);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('tranId')).toHaveText(tranId);
    await expect(page.getByTestId('cardNum')).toHaveText(/^\*{12}\d{4}$/);
    await expect(page.getByTestId('description')).toHaveText('Playwright purchase');
    await expect(page.getByTestId('amount')).toHaveText('+00000012.34');
    const details = page.getByTestId('details');
    for (const v of ['POS TERM', '2024-01-15', '2024-01-16', '000000001', 'Playwright Store', 'Seattle', '98101']) {
      await expect(details).toContainText(v);
    }
  });

  test('TS-COTRN02C-13 Q-COTRN02C-04 NAV-COTRN02C-06 F5 copies the latest transaction of the user into the form for review and writes nothing', async ({ page }) => {
    // Own source transaction: the highest in-scope ID, whatever ran before.
    await open(page, '/screens/COTRN02C', 'CT02 · COTRN02C');
    await page.getByLabel('Enter Acct #').fill(fixtures.linkedAccount);
    await fillSample(page);
    await page.getByLabel('Description', { exact: true }).fill('Playwright F5 source');
    await page.getByLabel('Amount', { exact: true }).fill('+00000043.21');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Confirm to add this transaction...');
    await page.getByLabel(/Please confirm/).fill('Y');
    await page.keyboard.press('Enter');
    const added = page.getByTestId('message').locator('.msg-text');
    await expect(added).toHaveText(/^Transaction added successfully\. {2}Your Tran ID is \d{16}\.$/);
    const sourceId = /(\d{16})/.exec((await added.textContent()) ?? '')![1];

    await page.getByLabel('Enter Acct #').fill(fixtures.linkedAccount);
    await page.getByLabel(/Please confirm/).fill('Y');
    await page.keyboard.press('F5');
    await expectMessage(page, 'Confirm to add this transaction...');
    await expect(page.getByLabel('Description', { exact: true })).toHaveValue('Playwright F5 source');
    await expect(page.getByLabel('Amount', { exact: true })).toHaveValue('+00000043.21');
    await expect(page.getByLabel('Type CD', { exact: true })).toHaveValue('01');
    await expect(page.getByLabel('Orig Date', { exact: true })).toHaveValue('2024-01-15');
    await expect(page.getByLabel('Proc Date', { exact: true })).toHaveValue('2024-01-16');
    await expect(page.getByLabel('Merchant Name', { exact: true })).toHaveValue('Playwright Store');
    await expect(page.getByLabel(/Please confirm/)).toHaveValue('');
    await expect(page.getByLabel(/Please confirm/)).toBeFocused();

    // Nothing was written on the F5 keystroke, even with Y typed: the next ID does not exist.
    const nextId = String(BigInt(sourceId) + 1n).padStart(16, '0');
    await open(page, '/screens/COTRN01C', 'CT01 · COTRN01C');
    await page.getByLabel('Enter Tran ID').fill(nextId);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Transaction ID NOT found...');
  });

  test('TS-COTRN02C-14 NAV-COTRN02C-04 NAV-COTRN02C-05 NAV-COTRN02C-07 keyboard only: F4 clears, other keys are invalid, F3 returns to the menu', async ({ page }) => {
    await menuOption(page, '8', 'CT02 · COTRN02C');
    await page.keyboard.type(fixtures.linkedAccount);
    await page.keyboard.press('Tab');
    await page.keyboard.type('4111');
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter Acct #')).toHaveValue('');
    await expect(page.getByLabel('Card #')).toHaveValue('');
    await expect(page.getByLabel('Enter Acct #')).toBeFocused();
    await page.keyboard.press('F9');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });
});
