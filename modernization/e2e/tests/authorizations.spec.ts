import { type Page, expect, test } from '@playwright/test';
import { MSG, expectMessage, expectNoSeriousA11yViolations, expectProgram, fixtures, signOn } from './support';

/** IMS sample (DBPAUTP0): account 7, linked to USER0002, has 50 pending authorizations, newest first. */
const ACCT = '00000000007';
const NEWEST_TXN = '40c56bdf89a74c1';
const SECOND_TXN = 'd347996bc29447e';
const CARD = '4859452612877065';

async function openList(page: Page) {
  await signOn(page, 'USER0002');
  await expectProgram(page, 'CM00 · COMEN01C');
  await page.getByRole('button', { name: /Pending Authorization View/ }).click();
  await expectProgram(page, 'CPVS · COPAUS0C');
}

async function search(page: Page, account: string) {
  await page.getByLabel('Search Acct Id').fill(account);
  await page.keyboard.press('Enter');
}

function txns(page: Page) {
  return page.getByTestId('auth-row').locator('td:nth-child(2)').allTextContents();
}

test.describe.configure({ mode: 'serial' });

test.describe('COPAUS0C pending authorization list', () => {
  test('TS-COPAUS0C-01 BR-COPAUS0C-05 BR-COPAUS0C-06 BR-COPAUS0C-09 C-MASK a regular user searches a linked account and sees its newest authorizations', async ({ page }) => {
    await openList(page);
    await expect(page.getByLabel('Search Acct Id')).toBeFocused();
    await expect(page.getByLabel('Search Acct Id')).toHaveValue('');
    expect(await txns(page)).toEqual([]);
    await expectNoSeriousA11yViolations(page);
    await search(page, ACCT);
    await expect.poll(() => txns(page)).toHaveLength(5);
    expect((await txns(page))[0]).toBe(NEWEST_TXN);
    expect((await txns(page))[1]).toBe(SECOND_TXN);
    await expect(page.getByTestId('name')).toHaveText('Cooper D Mayert');
    await expect(page.getByTestId('approved-count')).toHaveText('050');
    await expect(page.getByTestId('declined-count')).toHaveText('000');
    await expect(page.getByTestId('approved-amount')).toHaveText('74.79');
    await expect(page.getByTestId('page')).toHaveText('Page 1');
    const first = page.getByTestId('auth-row').first();
    await expect(first).toContainText('11/16/23');
    await expect(first).toContainText('14:17:49');
    await expect(first).toContainText('1.89');
    await expect(page.locator('main')).not.toContainText(CARD);
    await expectNoSeriousA11yViolations(page);
  });

  test('VR-COPAUS0C-01 VR-COPAUS0C-02 VR-COPAUS0C-03 TS-COPAUS0C-02 TS-COPAUS0C-03 TS-COPAUS0C-04 a missing or malformed account id and a bad select code are rejected', async ({ page }) => {
    await openList(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Please enter Acct Id...');
    await expect(page.getByLabel('Search Acct Id')).toHaveAttribute('aria-invalid', 'true');
    await expectNoSeriousA11yViolations(page);
    await search(page, '7');
    await expectMessage(page, 'Acct Id must be Numeric ...');
    await expect(page.getByLabel('Search Acct Id')).toHaveValue('');
    await search(page, ACCT);
    await expect.poll(() => txns(page)).toHaveLength(5);
    await page.locator('#sel2').fill('X');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Invalid selection. Valid value is S');
    await expect(page.locator('#sel2')).toHaveValue('');
    await expectNoSeriousA11yViolations(page);
  });

  test('A-2 C-SCOPE an account that is not linked to the user is answered as not in the cross-reference', async ({ page }) => {
    await openList(page);
    await search(page, fixtures.linkedAccount);
    await expectMessage(page, `Account:${fixtures.linkedAccount} not found in XREF file. Resp:000000013 Reas:000000080`);
    expect(await txns(page)).toEqual([]);
    await expect(page.getByTestId('name')).toHaveText('—');
  });

  test('TS-COPAUS0C-06 TS-COPAUS0C-07 TS-COPAUS0C-11 NAV-COPAUS0C-06 NAV-COPAUS0C-07 NAV-COPAUS0C-08 NAV-COPAUS0C-05 F8 and F7 page, other keys are invalid, F3 returns to the main menu', async ({ page }) => {
    await openList(page);
    await search(page, ACCT);
    await expect.poll(() => txns(page)).toHaveLength(5);
    const first = await txns(page);
    await page.keyboard.press('F7');
    await expectMessage(page, 'You are already at the top of the page...');
    await page.keyboard.press('F8');
    await expect(page.getByTestId('page')).toHaveText('Page 2');
    await expect.poll(async () => (await txns(page))[0]).not.toBe(first[0]);
    await page.keyboard.press('F7');
    await expect(page.getByTestId('page')).toHaveText('Page 1');
    await expect.poll(() => txns(page)).toEqual(first);
    await page.keyboard.press('F2');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });
});

test.describe('COPAUS1C authorization details', () => {
  test('TS-COPAUS0C-05 TS-COPAUS1C-01 TS-COPAUS1C-02 TS-COPAUS1C-03 TS-COPAUS1C-05 TS-COPAUS1C-06 NAV-COPAUS1C-04 keyboard: select with S, toggle fraud with F5, next with F8, F3 back to the list', async ({ page }) => {
    await signOn(page, 'USER0002');
    await expectProgram(page, 'CM00 · COMEN01C');
    await page.keyboard.type('11');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CPVS · COPAUS0C');
    await expect(page.getByLabel('Search Acct Id')).toBeFocused();
    await page.keyboard.type(ACCT);
    await page.keyboard.press('Enter');
    await expect.poll(() => txns(page)).toHaveLength(5);
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await expect(page.locator('#sel1')).toBeFocused();
    await page.keyboard.type('s');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CPVD · COPAUS1C');
    const details = page.getByTestId('auth-details');
    await expect(page.getByTestId('transaction-id')).toHaveText(NEWEST_TXN);
    await expect(details).toContainText('************7065');
    await expect(details).toContainText('0000-APPROVED');
    await expect(page.getByTestId('merchant-name')).toHaveText('Bestbuy.com');
    await expect(page.locator('main')).not.toContainText(CARD);
    await expectNoSeriousA11yViolations(page);

    await page.keyboard.press('F5');
    await expectMessage(page, 'AUTH MARKED FRAUD...');
    await expect(page.getByTestId('fraud-status')).toHaveText(/^F-\d\d\/\d\d\/\d\d$/);
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('F5');
    await expectMessage(page, 'AUTH FRAUD REMOVED...');
    await expect(page.getByTestId('fraud-status')).toHaveText(/^R-\d\d\/\d\d\/\d\d$/);

    await page.keyboard.press('F8');
    await expect(page.getByTestId('transaction-id')).toHaveText(SECOND_TXN);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CPVS · COPAUS0C');
    await expect(page.getByLabel('Search Acct Id')).toHaveValue(ACCT);
    await expect(page.getByTestId('page')).toHaveText('Page 1');
    await expect.poll(async () => (await txns(page))[0]).toBe(NEWEST_TXN);
  });

  test('TS-COPAUS1C-07 NAV-COPAUS1C-07 other keys keep the authorization on screen with the invalid key message', async ({ page }) => {
    await openList(page);
    await search(page, ACCT);
    await expect.poll(() => txns(page)).toHaveLength(5);
    await page.getByRole('button', { name: `View authorization ${SECOND_TXN}` }).click();
    await expectProgram(page, 'CPVD · COPAUS1C');
    await expect(page.getByTestId('transaction-id')).toHaveText(SECOND_TXN);
    await page.keyboard.press('F7');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByTestId('transaction-id')).toHaveText(SECOND_TXN);
    await expectNoSeriousA11yViolations(page);
  });
});
