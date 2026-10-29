import { expect, test } from '@playwright/test';
import { MSG, expectMessage, expectNoSeriousA11yViolations, expectProgram, fixtures, open, signOn } from './support';

test.describe('COSGN00C sign-on', () => {
  test('BR-COSGN00C-01 first entry shows an empty form with focus on User ID', async ({ page }) => {
    await open(page, '/', 'CC00 · COSGN00C');
    await expect(page.getByLabel('User ID')).toBeFocused();
    await expect(page.getByLabel('User ID')).toHaveValue('');
    await expectNoSeriousA11yViolations(page);
  });

  test('VR-COSGN00C missing user ID, then missing password, keep the typed user ID upper-cased', async ({ page }) => {
    await open(page, '/screens/COSGN00C', 'CC00 · COSGN00C');
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.NO_USER);
    await expect(page.getByLabel('User ID')).toBeFocused();
    await page.getByLabel('User ID').fill('user0001');
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.NO_PASSWORD);
    await expect(page.getByLabel('User ID')).toHaveValue('USER0001');
    await expect(page.getByLabel('Password')).toBeFocused();
    await expect(page.getByLabel('Password')).toHaveAttribute('aria-invalid', 'true');
    await expectNoSeriousA11yViolations(page);
  });

  test('D-SEC-EXTRAS wrong password and unknown user give the same message', async ({ page }) => {
    await signOn(page, 'USER0001', 'WRONGPW');
    await expectMessage(page, MSG.BAD_CREDENTIALS);
    await signOn(page, 'NOSUCHU', 'WRONGPW');
    await expectMessage(page, MSG.BAD_CREDENTIALS);
    await expectProgram(page, 'CC00 · COSGN00C');
  });

  test('NAV-COSGN00C F3 signs off with the farewell text; other F-keys are invalid', async ({ page }) => {
    await open(page, '/screens/COSGN00C', 'CC00 · COSGN00C');
    await page.keyboard.press('F9');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expect(page).toHaveURL(/\/signed-off$/);
    await expect.poll(() => page.getByTestId('farewell').textContent()).toBe(MSG.THANK_YOU);
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CC00 · COSGN00C');
  });

  test('A screen requested without a session shows a clean sign-on screen', async ({ page }) => {
    await page.goto('/screens/COBIL00C');
    await expectProgram(page, 'CC00 · COSGN00C');
    await expect(page.getByTestId('message')).toHaveText('');
  });
});

test.describe('Menus and roles', () => {
  test('NAV-COSGN00C a regular user lands on the main menu; invalid option is rejected', async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
    await expect(page.getByTestId('user')).toContainText('USER0001');
    await expect(page.getByTestId('user')).toContainText('User');
    await page.getByLabel('Please select an option').fill('99');
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BAD_OPTION);
    await expectNoSeriousA11yViolations(page);
  });

  test('C-ROLE an admin lands on the admin menu; a user is turned away from it', async ({ page, browser }) => {
    await signOn(page, 'ADMIN001');
    await expectProgram(page, 'CA00 · COADM01C');
    await expect(page.getByTestId('user')).toContainText('Admin');
    await expectNoSeriousA11yViolations(page);

    const other = await browser.newPage();
    await signOn(other, 'USER0001');
    await expectProgram(other, 'CM00 · COMEN01C');
    await other.goto('/screens/COADM01C');
    await expectProgram(other, 'CM00 · COMEN01C');
    await expectMessage(other, MSG.ADMIN_ONLY);
    await other.close();
  });

  test('NAV-COMEN01C-03 TS-COMEN01C-09 an invalid key keeps the typed option on the main menu', async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
    await page.getByLabel('Please select an option').fill('4');
    await page.keyboard.press('F7');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByLabel('Please select an option')).toHaveValue('4');
    await expectNoSeriousA11yViolations(page);
  });

  test('NAV-COADM01C-03 TS-COADM01C-07 an invalid key keeps the typed option on the admin menu', async ({ page }) => {
    await signOn(page, 'ADMIN001');
    await expectProgram(page, 'CA00 · COADM01C');
    await page.getByLabel('Please select an option').fill('3');
    await page.keyboard.press('F2');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByLabel('Please select an option')).toHaveValue('3');
  });

  test('the admin menu sends the next key after a not-installed message', async ({ page }) => {
    // Every option is installed in the e2e deployment, so the answer to option 5 is replaced in the browser and
    // the server never enters the not-installed state; only the UI side is shown here. The rule itself
    // (Q-COADM01C-07, TS-COADM01C-10) is checked against the server in DisabledOptionsTest.
    const NOT_INSTALLED = 'This option is not installed ...';
    await page.route('**/api/screens/COADM01C', async (route) => {
      const body = route.request().postDataJSON() as { key?: string; option?: string } | null;
      if (route.request().method() !== 'POST' || body?.key !== 'ENTER' || body.option?.trim() !== '5') return route.continue();
      const shown = await (await page.request.get('/api/screens/COADM01C')).json();
      await route.fulfill({ json: { ...shown, fields: { ...shown.fields, option: '05' }, message: NOT_INSTALLED, messageColor: 'GREEN' } });
    });
    const notInstalled = async () => {
      await page.getByLabel('Please select an option').fill('5');
      await page.keyboard.press('Enter');
      await expectMessage(page, NOT_INSTALLED);
    };
    await signOn(page, 'ADMIN001');
    await expectProgram(page, 'CA00 · COADM01C');

    await notInstalled();
    await page.getByLabel('Please select an option').fill('7');
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BAD_OPTION);

    await notInstalled();
    await page.keyboard.press('F2');
    await expectMessage(page, MSG.INVALID_KEY);

    await notInstalled();
    await page.getByLabel('Please select an option').fill('1');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU00 · COUSR00C');

    await page.goto('/screens/COADM01C');
    await expectProgram(page, 'CA00 · COADM01C');
    await notInstalled();
    await page.keyboard.press('F3');
    await expectProgram(page, 'CC00 · COSGN00C');
  });

  test('NAV-COMEN01C F3 from the main menu returns to sign-on and ends the session', async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CC00 · COSGN00C');
    await page.goto('/screens/COMEN01C');
    await expectProgram(page, 'CC00 · COSGN00C');
  });
});

test.describe('COBIL00C bill payment', () => {
  test.beforeEach(async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
    await page.getByRole('button', { name: /Bill Payment/ }).click();
    await expectProgram(page, 'CB00 · COBIL00C');
  });

  test('VR-COBIL00C-01 VR-COBIL00C-03 VR-COBIL00C-02 C-SCOPE empty, out-of-scope, and bad confirm values are rejected', async ({ page }) => {
    await expect(page.getByLabel('Enter Acct ID')).toBeFocused();
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BIL_EMPTY);
    await page.getByLabel('Enter Acct ID').fill(fixtures.unlinkedAccount);
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BIL_NOT_FOUND);
    await page.getByLabel('Enter Acct ID').fill(fixtures.linkedAccount);
    await page.getByLabel(/Please confirm/).fill('X');
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BIL_YN);
    await expect(page.getByLabel(/Please confirm/)).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COBIL00C-01 TS-COBIL00C-03 NAV-COBIL00C-03 D-RECEIPT pay the full balance: confirm prompt, success message, receipt, zero balance after', async ({ page }) => {
    await page.getByLabel('Enter Acct ID').fill(fixtures.linkedAccount);
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BIL_CONFIRM);
    await expect(page.getByLabel(/Please confirm/)).toBeFocused();
    const shown = await page.getByTestId('balance').getAttribute('data-raw');
    expect(Number(shown)).toBeGreaterThan(0);
    // CURBAL is edited as +9999999999.99; only the D-RECEIPT panel uses currency.
    await expect(page.getByTestId('balance')).toHaveText(shown ?? '');
    expect(shown).toMatch(/^[+-]\d{10}\.\d{2}$/);
    await page.getByLabel(/Please confirm/).fill('y');
    await page.keyboard.press('Enter');
    const message = page.getByTestId('message').locator('.msg-text');
    await expect(message).toHaveText(/^Payment successful\.  Your Transaction ID is \d{16}\.$/);
    const tranId = /(\d{16})/.exec((await message.textContent()) ?? '')![1];
    const receipt = page.getByTestId('receipt');
    await expect(receipt).toContainText(tranId);
    await expect(receipt).toContainText(fixtures.linkedAccount);
    await expect(receipt).toContainText('$0.00');
    await expect(page.getByLabel('Enter Acct ID')).toHaveValue('');
    await expectNoSeriousA11yViolations(page);

    await page.getByLabel('Enter Acct ID').fill(fixtures.linkedAccount);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'You have nothing to pay...');
  });

  test('TS-COBIL00C-09 NAV-COBIL00C-04 NAV-COBIL00C-05 NAV-COBIL00C-06 F4 clears the form, other keys are invalid, and F3 returns to the main menu', async ({ page }) => {
    await page.getByLabel('Enter Acct ID').fill(fixtures.unlinkedAccount);
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter Acct ID')).toHaveValue('');
    await page.getByLabel('Enter Acct ID').fill(fixtures.linkedAccount);
    await page.keyboard.press('F5');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByLabel('Enter Acct ID')).toHaveValue(fixtures.linkedAccount);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });
});
