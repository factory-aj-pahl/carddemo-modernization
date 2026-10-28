import { expect, test } from '@playwright/test';
import { expectMessage, expectNoSeriousA11yViolations, expectProgram, fixtures, open, signOn } from './support';

const notInXref = (account: string) => `Account:${account} not found in Cross ref file.  Resp:000000013  Reas:0000`;

test.describe('COACTVWC account view', () => {
  test.beforeEach(async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
    await open(page, '/screens/COACTVWC', 'CAVW · COACTVWC');
  });

  test('TS-COACTVWC-01 NAV-COACTVWC-03 BR-COACTVWC-06 BR-COACTVWC-08 first entry shows the empty search screen', async ({ page }) => {
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await expect(page.getByLabel('Account Number')).toHaveValue('');
    await expect(page.getByTestId('info')).toHaveText('Enter or update id of account to display');
    await expect(page.getByTestId('message')).toHaveText('');
    await expect(page.getByTestId('customerId')).toHaveText('—');
    await expectNoSeriousA11yViolations(page);
  });

  test('VR-COACTVWC-01 VR-COACTVWC-02 blank and malformed account numbers are rejected', async ({ page }) => {
    await page.keyboard.press('Enter');
    await expectMessage(page, 'No input received');
    await expect(page.getByLabel('Account Number')).toHaveAttribute('aria-invalid', 'true');
    await page.getByLabel('Account Number').fill('123');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Account Filter must  be a non-zero 11 digit number');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COACTVWC-02 BR-COACTVWC-01 C-MASK a linked account shows its details with SSN and government ID masked', async ({ page }) => {
    await page.getByLabel('Account Number').fill(fixtures.linkedAccount);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('info')).toHaveText('Displaying details of given Account');
    await expect(page.getByTestId('active')).toHaveText(/^[YN]$/);
    await expect(page.getByTestId('customerId')).toHaveText(/^\d{9}$/);
    await expect(page.getByTestId('ssn')).toHaveText(/^\*\*\*-\*\*-\d{4}$/);
    await expect(page.getByTestId('govtId')).toHaveText(/^\*+.{0,4}$/);
    await expect(page.getByTestId('creditLimit')).toHaveText(/^[+-] {0,10}[\d,]*\.\d\d$/);
    await expect(page.getByTestId('message')).toHaveText('');
    await expectNoSeriousA11yViolations(page);
  });

  test('ERR-COACTVWC-01 C-SCOPE an account not linked to the user is not found', async ({ page }) => {
    await page.getByLabel('Account Number').fill(fixtures.unlinkedAccount);
    await page.keyboard.press('Enter');
    await expectMessage(page, notInXref(fixtures.unlinkedAccount));
    await expect(page.getByTestId('customerId')).toHaveText('—');
  });

  test('NAV-COACTVWC-01 NAV-COACTVWC-02 keyboard only: other keys act like ENTER and F3 returns to the menu', async ({ page }) => {
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await page.keyboard.type(fixtures.linkedAccount);
    await page.keyboard.press('F7');
    await expect(page.getByTestId('info')).toHaveText('Displaying details of given Account');
    await page.keyboard.press('Enter');
    // F7 already showed the account, so wait for the ENTER turn to finish before pressing F3.
    await expect(page.locator('main[data-ready]')).toBeVisible();
    await expect(page.getByTestId('ssn')).toHaveText(/^\*\*\*-\*\*-\d{4}$/);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });
});

test.describe('COACTUPC account update', () => {
  test.beforeEach(async ({ page }) => {
    await signOn(page, 'ADMIN001');
    await expectProgram(page, 'CA00 · COADM01C');
    await open(page, '/screens/COACTUPC', 'CAUP · COACTUPC');
  });

  const form = (page: import('@playwright/test').Page) => page.getByTestId('form');

  test('TS-COACTUPC-01 NAV-COACTUPC-03 BR-COACTUPC-23 first entry shows the empty search screen', async ({ page }) => {
    await expect(form(page)).toHaveAttribute('data-mode', 'SEARCH');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await expect(page.getByTestId('info')).toHaveText('Enter or update id of account to update');
    await expect(page.getByLabel('First Name')).toHaveAttribute('readonly', '');
    await expect(page.getByRole('button', { name: /Save/ })).toHaveCount(0);
    await expectNoSeriousA11yViolations(page);
  });

  test('VR-COACTUPC-01 VR-COACTUPC-02 VR-COACTUPC-03 missing, malformed, and unknown account numbers are rejected', async ({ page }) => {
    await page.keyboard.press('Enter');
    await expectMessage(page, 'No input received');
    await expect(page.getByLabel('Account Number')).toHaveAttribute('aria-invalid', 'true');
    await page.getByLabel('Account Number').fill('0000000002X');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Account Number if supplied must be a 11 digit Non-Zero Number');
    await page.getByLabel('Account Number').fill('99999999999');
    await page.keyboard.press('Enter');
    await expectMessage(page, notInXref('99999999999'));
    await expect(form(page)).toHaveAttribute('data-mode', 'SEARCH');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COACTUPC-05 TS-COACTUPC-08 TS-COACTUPC-09 fetch, change, validate, and save with F5', async ({ page }) => {
    await page.getByLabel('Account Number').fill(fixtures.unlinkedAccount);
    await page.keyboard.press('Enter');
    await expect(form(page)).toHaveAttribute('data-mode', 'S');
    await expect(page.getByTestId('info')).toHaveText('Update account details presented above.');
    await expect(page.getByLabel('SSN first 3')).toHaveValue('***');
    await expect(page.getByLabel('Account Number')).toHaveAttribute('readonly', '');
    await expectNoSeriousA11yViolations(page);

    await page.keyboard.press('Enter');
    await expectMessage(page, 'No change detected with respect to values fetched.');

    await page.getByLabel('Middle Name').fill('Playwright');
    await page.keyboard.press('Enter');
    await expect(form(page)).toHaveAttribute('data-mode', 'N');
    await expect(page.getByTestId('info')).toHaveText('Changes validated.Press F5 to save');
    await expect(page.getByRole('button', { name: /Save/ }).first()).toBeVisible();
    await expect(page.getByLabel('Middle Name')).toHaveAttribute('readonly', '');
    await expectNoSeriousA11yViolations(page);

    await page.keyboard.press('F5');
    await expect(form(page)).toHaveAttribute('data-mode', 'C');
    await expect(page.getByTestId('info')).toHaveText('Changes committed to database');
    await expect(page.getByLabel('Middle Name')).toHaveValue('Playwright');
  });

  test('VR-COACTUPC-07 TS-COACTUPC-21 an invalid edit marks the field and keeps F12 available', async ({ page }) => {
    await page.getByLabel('Account Number').fill(fixtures.unlinkedAccount);
    await page.keyboard.press('Enter');
    await expect(form(page)).toHaveAttribute('data-mode', 'S');
    await page.getByLabel('Active Y/N').fill('X');
    await page.keyboard.press('Enter');
    await expect(form(page)).toHaveAttribute('data-mode', 'E');
    await expectMessage(page, 'Account Status must be Y or N.');
    await expect(page.getByLabel('Active Y/N')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Active Y/N')).toBeFocused();
    await expect(page.getByRole('button', { name: /Cancel/ }).first()).toBeVisible();
    await expectNoSeriousA11yViolations(page);
  });

  test('Q-COACTUPC-31 TS-COACTUPC-32 every invalid SSN part is highlighted, also when part 1 is invalid', async ({ page }) => {
    await page.getByLabel('Account Number').fill(fixtures.unlinkedAccount);
    await page.keyboard.press('Enter');
    await expect(form(page)).toHaveAttribute('data-mode', 'S');
    await page.locator('#ssn1').fill('ABC');
    await page.locator('#ssn2').fill('45');
    await page.locator('#ssn3').fill('12X4');
    await page.keyboard.press('Enter');
    await expect(form(page)).toHaveAttribute('data-mode', 'E');
    await expectMessage(page, 'SSN: First 3 chars must be all numeric.');
    await expect(page.locator('#ssn1')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('#ssn3')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('#ssn2')).not.toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('#ssn1')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('NAV-COACTUPC-08 NAV-COACTUPC-01 NAV-COACTUPC-02 keyboard only: F12 discards changes, other keys act like ENTER, F3 exits', async ({ page }) => {
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await page.keyboard.type(fixtures.unlinkedAccount);
    await page.keyboard.press('F8');
    await expect(form(page)).toHaveAttribute('data-mode', 'S');
    await expect(page.getByLabel('Active Y/N')).toBeFocused();
    const before = await page.getByLabel('Active Y/N').inputValue();
    await page.keyboard.press('Tab');
    await page.keyboard.type('ZZZ');
    await page.keyboard.press('Enter');
    await expect(form(page)).not.toHaveAttribute('data-mode', 'S');
    await page.keyboard.press('F12');
    await expect(form(page)).toHaveAttribute('data-mode', 'S');
    await expect(page.getByLabel('Active Y/N')).toHaveValue(before);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });
});

test('C-ROLE a user is told an unlinked account is not found on the update screen', async ({ page }) => {
  await signOn(page, 'USER0001');
  await expectProgram(page, 'CM00 · COMEN01C');
  await open(page, '/screens/COACTUPC', 'CAUP · COACTUPC');
  await page.getByLabel('Account Number').fill(fixtures.unlinkedAccount);
  await page.keyboard.press('Enter');
  await expectMessage(page, notInXref(fixtures.unlinkedAccount));
  await expect(page.getByTestId('form')).toHaveAttribute('data-mode', 'SEARCH');
});
