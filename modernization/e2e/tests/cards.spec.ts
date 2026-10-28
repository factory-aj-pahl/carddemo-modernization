import { type Page, expect, test } from '@playwright/test';
import { expectMessage, expectNoSeriousA11yViolations, expectProgram, fixtures, signOn } from './support';

/** carddata.txt: cards on accounts linked to USER0001 in the sample data. */
const VIEW_CARD = '9680294154603697';
const VIEW_ACCT = '00000000001';
const UPDATE_CARD = '7427684863423209';
const UPDATE_ACCT = '00000000011';

const masked = (card: string) => '************' + card.slice(12);

async function mainMenu(page: Page) {
  await signOn(page, 'USER0001');
  await expectProgram(page, 'CM00 · COMEN01C');
}

async function openOption(page: Page, name: RegExp, tranAndProgram: string) {
  await mainMenu(page);
  await page.getByRole('button', { name }).click();
  await expectProgram(page, tranAndProgram);
}

function accountNumbers(page: Page) {
  return page.getByTestId('card-row').locator('td:nth-child(2)').allTextContents();
}

function cardNumbers(page: Page) {
  return page.getByTestId('card-row').locator('td:nth-child(3)').allTextContents();
}

async function expectInfo(page: Page, text: string) {
  await expect.poll(() => page.getByTestId('info').textContent()).toBe(text);
}

test.describe.configure({ mode: 'serial' });

test.describe('COCRDLIC credit card list', () => {
  test('Q-COCRDLIC-01 C-SCOPE TS-COCRDLIC-21 BR-COCRDLIC-01 NAV-COCRDLIC-09 C-MASK a regular user sees only the cards of linked accounts, masked', async ({ page }) => {
    await openOption(page, /Credit Card List/, 'CCLI · COCRDLIC');
    const cards = await cardNumbers(page);
    expect(cards).toHaveLength(7);
    for (const c of cards) expect(c).toMatch(/^\*{12}\d{4}$/);
    await expect(page.getByTestId('page')).toHaveText('Page 1');
    await expectInfo(page, 'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await expect(page.locator('main')).not.toContainText(VIEW_CARD);
    await expectNoSeriousA11yViolations(page);

    // Every listed card, on every page, belongs to an account linked to USER0001.
    const shown = [...(await accountNumbers(page))];
    await page.keyboard.press('F8');
    await expect(page.getByTestId('page')).toHaveText('Page 2');
    shown.push(...(await accountNumbers(page)));
    expect(shown).toHaveLength(fixtures.userCardCount);
    for (const a of shown) expect(fixtures.userAccounts).toContain(a);
    expect(shown).not.toContain(fixtures.unlinkedAccount);
  });

  test('VR-COCRDLIC-01 VR-COCRDLIC-04 TS-COCRDLIC-15 a malformed account filter and an invalid action code are rejected', async ({ page }) => {
    await openOption(page, /Credit Card List/, 'CCLI · COCRDLIC');
    await page.getByLabel('Account Number').fill('12AB');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER');
    await expect(page.getByLabel('Account Number')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('input.sel').first()).toBeDisabled();
    await expectNoSeriousA11yViolations(page);
    await page.getByLabel('Account Number').fill('');
    await page.keyboard.press('Enter');
    // The rebuilt list re-renders the Sel fields; typing before the turn ends would be wiped.
    await expect(page.getByLabel('Account Number')).not.toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('#sel2')).toBeEnabled();
    await page.locator('#sel2').fill('x');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'INVALID ACTION CODE');
    await expect(page.locator('#sel2')).toHaveAttribute('aria-invalid', 'true');
    await expectNoSeriousA11yViolations(page);
  });

  test('A-2 BR-COCRDLIC-03 a filter on an account outside the scope finds nothing; a linked account shows its card', async ({ page }) => {
    await openOption(page, /Credit Card List/, 'CCLI · COCRDLIC');
    await page.getByLabel('Account Number').fill(fixtures.unlinkedAccount);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.');
    expect(await cardNumbers(page)).toEqual([]);
    await page.getByLabel('Account Number').fill(VIEW_ACCT);
    await page.keyboard.press('Enter');
    await expect.poll(() => cardNumbers(page)).toEqual([masked(VIEW_CARD)]);
  });

  test('TS-COCRDLIC-02 TS-COCRDLIC-04 TS-COCRDLIC-05 NAV-COCRDLIC-04 NAV-COCRDLIC-05 TS-COCRDLIC-16 F8 and F7 page, F3 returns to the main menu', async ({ page }) => {
    await openOption(page, /Credit Card List/, 'CCLI · COCRDLIC');
    const first = await cardNumbers(page);
    await page.keyboard.press('F7');
    await expectMessage(page, 'NO PREVIOUS PAGES TO DISPLAY');
    await page.keyboard.press('F8');
    await expect(page.getByTestId('page')).toHaveText('Page 2');
    await expect.poll(async () => (await cardNumbers(page)).length).toBe(3);
    await expectMessage(page, 'NO MORE RECORDS TO SHOW');
    await page.keyboard.press('F8');
    await expectMessage(page, 'NO MORE PAGES TO DISPLAY');
    await page.keyboard.press('F7');
    await expect(page.getByTestId('page')).toHaveText('Page 1');
    await expect.poll(() => cardNumbers(page)).toEqual(first);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });

  test('TS-COCRDLIC-12 NAV-COCRDLIC-06 NAV-COCRDLIC-03 keyboard: S on a row opens the view, F3 comes back', async ({ page }) => {
    await mainMenu(page);
    await page.keyboard.type('3');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CCLI · COCRDLIC');
    const shown = (await cardNumbers(page))[0];
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await expect(page.locator('#sel1')).toBeFocused();
    await page.keyboard.type('s');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CCDL · COCRDSLC');
    await expect(page.getByLabel('Card Number')).toHaveValue(shown);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CCLI · COCRDLIC');
  });
});

test.describe('COCRDSLC credit card view', () => {
  test('TS-COCRDSLC-02 TS-COCRDSLC-04 VR-COCRDSLC-05 empty search screen, then no input', async ({ page }) => {
    await openOption(page, /Credit Card View/, 'CCDL · COCRDSLC');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await expectInfo(page, 'Please enter Account and Card Number');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'No input received');
    await expect(page.getByLabel('Account Number')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Card Number')).toHaveAttribute('aria-invalid', 'true');
    await expectNoSeriousA11yViolations(page);
  });

  test('A-2 VR-COCRDSLC-06 a card outside the scope is answered as not found', async ({ page }) => {
    await openOption(page, /Credit Card View/, 'CCDL · COCRDSLC');
    await page.getByLabel('Account Number').fill(fixtures.unlinkedAccount);
    await page.getByLabel('Card Number').fill(VIEW_CARD);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Did not find cards for this search condition');
    await expect(page.getByTestId('card-details')).not.toContainText('Immanuel');
  });

  test('TS-COCRDSLC-03 BR-COCRDSLC-01 NAV-COCRDSLC-02 keyboard: typed keys show the card with the number masked, F3 returns', async ({ page }) => {
    await mainMenu(page);
    await page.keyboard.type('4');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CCDL · COCRDSLC');
    await page.keyboard.type(VIEW_ACCT);
    await page.keyboard.press('Tab');
    await page.keyboard.type(VIEW_CARD);
    await page.keyboard.press('Enter');
    await expectInfo(page, '   Displaying requested details');
    const details = page.getByTestId('card-details');
    await expect(details).toContainText('Immanuel Kessler');
    await expect(details).toContainText('05 / 2025');
    await expect(details).toContainText('Y');
    await expect(page.getByLabel('Card Number')).toHaveValue(masked(VIEW_CARD));
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('Enter');
    await expectInfo(page, '   Displaying requested details');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CM00 · COMEN01C');
  });
});

test.describe('COCRDUPC credit card update', () => {
  test('TS-COCRDUPC-16 NAV-COCRDUPC-05 VR-COCRDUPC-04 search screen, then a malformed card number', async ({ page }) => {
    await openOption(page, /Credit Card Update/, 'CCUP · COCRDUPC');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await expectInfo(page, 'Please enter Account and Card Number');
    await expect(page.getByLabel('Name on card')).toHaveAttribute('readonly', '');
    await expectNoSeriousA11yViolations(page);
    await page.getByLabel('Account Number').fill(UPDATE_ACCT);
    await page.getByLabel('Card Number').fill('1234');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER');
    await expect(page.getByLabel('Card Number')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Card Number')).toHaveValue('1234');
    await expectNoSeriousA11yViolations(page);
  });

  test('VR-COCRDUPC-02 a non-numeric account is shown red as typed', async ({ page }) => {
    await openOption(page, /Credit Card Update/, 'CCUP · COCRDUPC');
    await page.getByLabel('Account Number').fill('0000000001A');
    await page.getByLabel('Card Number').fill(UPDATE_CARD);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER');
    await expect(page.getByLabel('Account Number')).toHaveValue('0000000001A');
    await expect(page.getByLabel('Account Number')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await expectInfo(page, 'Please enter Account and Card Number');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COCRDUPC-08 VR-COCRDUPC-08 BR-COCRDUPC-10 a name with digits is rejected; F12 re-reads the stored values', async ({ page }) => {
    await openOption(page, /Credit Card Update/, 'CCUP · COCRDUPC');
    await page.getByLabel('Account Number').fill(UPDATE_ACCT);
    await page.getByLabel('Card Number').fill(UPDATE_CARD);
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Details of selected card shown above');
    await expect(page.getByLabel('Name on card')).toHaveValue('HAYDEN PFANNERSTILL');
    await page.getByLabel('Name on card').fill('R2D2');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Card name can only contain alphabets and spaces');
    await expect(page.getByLabel('Name on card')).toHaveAttribute('aria-invalid', 'true');
    await expectInfo(page, 'Update card details presented above.');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('F12');
    await expectInfo(page, 'Details of selected card shown above');
    await expect(page.getByLabel('Name on card')).toHaveValue('HAYDEN PFANNERSTILL');
  });

  test('TS-COCRDUPC-03 TS-COCRDUPC-04 TS-COCRDUPC-24 keyboard: fetch, change, confirm with F5, and the change is stored', async ({ page }) => {
    await mainMenu(page);
    await page.keyboard.type('5');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CCUP · COCRDUPC');
    await page.keyboard.type(UPDATE_ACCT);
    await page.keyboard.press('Tab');
    await page.keyboard.type(UPDATE_CARD);
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Details of selected card shown above');
    await expect(page.getByLabel('Name on card')).toBeFocused();
    await page.keyboard.press('Control+A');
    await page.keyboard.type('HAYDEN P SMITH');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Control+A');
    await page.keyboard.type('11');
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Changes validated.Press F5 to save');
    await expect(page.getByRole('button', { name: /F5\s*Save/ }).first()).toBeVisible();
    await expect(page.getByLabel('Name on card')).toHaveAttribute('readonly', '');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('F5');
    await expectInfo(page, 'Changes committed to database');
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Please enter Account and Card Number');
    await page.getByLabel('Account Number').fill(UPDATE_ACCT);
    await page.getByLabel('Card Number').fill(UPDATE_CARD);
    await page.keyboard.press('Enter');
    await expect(page.getByLabel('Name on card')).toHaveValue('HAYDEN P SMITH');
    await expect(page.getByLabel('Expiry Month')).toHaveValue('11');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COCRDUPC-01 NAV-COCRDUPC-04 NAV-COCRDUPC-02 from the list the card is fetched with read-only keys; F3 returns to the list', async ({ page }) => {
    await openOption(page, /Credit Card List/, 'CCLI · COCRDLIC');
    const shown = (await cardNumbers(page))[0];
    await page.getByRole('button', { name: `Update card ending ${shown.slice(-4)}` }).click();
    await expectProgram(page, 'CCUP · COCRDUPC');
    await expect(page.getByLabel('Card Number')).toHaveValue(shown);
    await expect(page.getByLabel('Card Number')).toHaveAttribute('readonly', '');
    await expectInfo(page, 'Details of selected card shown above');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CCLI · COCRDLIC');
  });
});
