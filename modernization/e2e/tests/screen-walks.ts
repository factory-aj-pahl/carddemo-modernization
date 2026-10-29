import { type Locator, type Page, expect } from '@playwright/test';
import { DEMO_PASSWORD, MSG, expectMessage, expectProgram, fixtures } from './support';

/**
 * Keyboard-only walks through every BMS map's screen, shared by the accessibility suite (axe at each
 * state) and the UI-review capture (a screenshot at each state). Only page.goto (a typed URL) and the
 * keyboard are used: Tab, Shift+Tab, arrows, Space, typing, Enter, and the F-keys.
 */
export type At = (state: string, how: string) => Promise<void>;

export interface ScreenWalk {
  map: string;
  program: string;
  /** Rule IDs the keyboard flow asserts (test-title prefix). */
  flowIds: string[];
  /** Initial, validation-error, and populated (or closest real) states, in that order. */
  states: (page: Page, at: At) => Promise<void>;
  /** Primary flow plus every F-key of the NAV rules, with extra states (paging, confirm, result). */
  flow: (page: Page, at: At) => Promise<void>;
}

/** Sample accounts and cards linked to USER0001 that no other spec uses. */
const PAY_ACCOUNT = '00000000046';
const PREVIEW_ACCOUNT = '00000000036';
const UPDATE_ACCOUNT = '00000000041';
const CARD_ACCOUNT = '00000000046';
const CARD = '5656830544981216';
/** IMS sample: account 7 (USER0002) has 50 pending authorizations. */
const AUTH_ACCOUNT = '00000000007';
/** Created, changed, and deleted by the COUSR01C-03C flows, in that order. */
export const A11Y_USER = 'A11YUSR1';
const NEW_TYPE = '42';

const isFocused = (target: Locator) => target.evaluate((el) => el === document.activeElement);

/** Tab (or Shift+Tab) until the target has focus; fails if the keyboard cannot reach it. */
export async function tabTo(page: Page, target: Locator, back = false, max = 80) {
  for (let i = 0; i < max && !(await isFocused(target)); i++) await page.keyboard.press(back ? 'Shift+Tab' : 'Tab');
  await expect(target).toBeFocused();
}

/** Move to a field with the keyboard and replace its content by typing. */
export async function typeInto(page: Page, target: Locator, text: string, back = false) {
  await tabTo(page, target, back);
  await page.keyboard.press('ControlOrMeta+a');
  if (text) await page.keyboard.type(text);
  else await page.keyboard.press('Delete');
}

const ready = (page: Page) => expect(page.locator('main[data-ready]')).toBeVisible();
const msgText = (page: Page) => page.getByTestId('message').locator('.msg-text');
const info = (page: Page) => page.getByTestId('info');
async function expectInfo(page: Page, text: string) {
  await expect.poll(() => info(page).textContent()).toBe(text);
}
async function expectNoInvalidKey(page: Page) {
  await ready(page);
  await expect(page.getByTestId('message')).not.toContainText('Invalid key');
}

export async function signOnKb(page: Page, userId: string, lands: string) {
  await page.goto('/screens/COSGN00C');
  await expectProgram(page, 'CC00 · COSGN00C');
  await expect(page.getByLabel('User ID')).toBeFocused();
  await page.keyboard.type(userId);
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Password')).toBeFocused();
  await page.keyboard.type(DEMO_PASSWORD);
  await page.keyboard.press('Enter');
  await expectProgram(page, lands);
}

async function choose(page: Page, option: string, lands: string) {
  await typeInto(page, page.getByLabel('Please select an option'), option);
  await page.keyboard.press('Enter');
  await expectProgram(page, lands);
}

const MAIN = 'CM00 · COMEN01C';
const ADMIN = 'CA00 · COADM01C';
const user = (page: Page) => signOnKb(page, 'USER0001', MAIN);
const admin = (page: Page) => signOnKb(page, 'ADMIN001', ADMIN);

// ---------------------------------------------------------------- sign-on and menus

const COSGN00: ScreenWalk = {
  map: 'COSGN00',
  program: 'COSGN00C',
  flowIds: ['NAV-COSGN00C-01', 'NAV-COSGN00C-02', 'NAV-COSGN00C-03', 'NAV-COSGN00C-04'],
  async states(page, at) {
    await page.goto('/screens/COSGN00C');
    await expectProgram(page, 'CC00 · COSGN00C');
    await expect(page.getByLabel('User ID')).toBeFocused();
    await expect(page.getByLabel('User ID')).toHaveValue('');
    await at('initial', 'Open /screens/COSGN00C without a session.');
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.NO_USER);
    await expect(page.getByLabel('User ID')).toHaveAttribute('aria-invalid', 'true');
    await at('error', 'ENTER with both fields empty: "Please enter User ID ...".');
    await page.keyboard.type('user0001');
    await page.keyboard.press('Tab');
    await page.keyboard.type(DEMO_PASSWORD);
    await expect(page.getByLabel('User ID')).toHaveValue('USER0001');
    await expect(page.getByLabel('Password')).toHaveValue(DEMO_PASSWORD);
    await at('populated', 'User ID and Password typed (closest real state: the map shows no data), before ENTER.');
  },
  async flow(page, at) {
    await page.goto('/screens/COSGN00C');
    await expectProgram(page, 'CC00 · COSGN00C');
    await page.keyboard.press('F9');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expect(page).toHaveURL(/\/signed-off$/);
    await expect.poll(() => page.getByTestId('farewell').textContent()).toBe(MSG.THANK_YOU);
    await at('farewell', 'F3 on the sign-on screen: the plain-text farewell (route /signed-off).');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CC00 · COSGN00C');
    await page.keyboard.type('user0001');
    await page.keyboard.press('Tab');
    await page.keyboard.type(DEMO_PASSWORD);
    await page.keyboard.press('Enter');
    await expectProgram(page, MAIN);
    await signOnKb(page, 'ADMIN001', ADMIN);
  },
};

function menu(map: string, program: 'COMEN01C' | 'COADM01C'): ScreenWalk {
  const main = program === 'COMEN01C';
  const lands = main ? MAIN : ADMIN;
  const signOn = main ? user : admin;
  return {
    map,
    program,
    flowIds: main
      ? ['NAV-COMEN01C-01', 'NAV-COMEN01C-02', 'NAV-COMEN01C-03', 'NAV-COMEN01C-04']
      : ['NAV-COADM01C-01', 'NAV-COADM01C-02', 'NAV-COADM01C-03', 'NAV-COADM01C-04'],
    async states(page, at) {
      await signOn(page);
      const option = page.getByLabel('Please select an option');
      await expect(option).toBeFocused();
      await expect(page.getByRole('button', { name: main ? /Bill Payment/ : /User List/ })).toBeVisible();
      await at('initial', `Sign on as ${main ? 'USER0001' : 'ADMIN001'}; the menu is the first screen.`);
      await page.keyboard.type('99');
      await page.keyboard.press('Enter');
      await expectMessage(page, MSG.BAD_OPTION);
      await expect(option).toHaveAttribute('aria-invalid', 'true');
      await at('error', 'Option 99 and ENTER: "Please enter a valid option number...".');
      await typeInto(page, option, '4');
      await page.keyboard.press('F7');
      await expectMessage(page, MSG.INVALID_KEY);
      await expect(option).toHaveValue('4');
      await at('populated', 'Closest real state (a menu shows no data): option 4 typed, then F7; the typed option is kept with "Invalid key pressed".');
    },
    async flow(page, at) {
      await signOn(page);
      await page.keyboard.type('5');
      await page.keyboard.press('F7');
      await expectMessage(page, MSG.INVALID_KEY);
      await expect(page.getByLabel('Please select an option')).toHaveValue('5');
      if (main) {
        await choose(page, '10', 'CB00 · COBIL00C');
        await page.keyboard.press('F3');
        await expectProgram(page, MAIN);
        // The option buttons are keyboard operable too.
        await tabTo(page, page.getByRole('button', { name: /Credit Card List/ }), true);
        await page.keyboard.press('Enter');
        await expectProgram(page, 'CCLI · COCRDLIC');
      } else {
        await choose(page, '1', 'CU00 · COUSR00C');
        await page.keyboard.press('F3');
        await expectProgram(page, ADMIN);
        await choose(page, '6', 'CTTU · COTRTUPC');
        await page.keyboard.press('F3');
        await expectProgram(page, ADMIN);
        await tabTo(page, page.getByRole('button', { name: /User Add/ }), true);
        await page.keyboard.press('Enter');
        await expectProgram(page, 'CU01 · COUSR01C');
      }
      await page.keyboard.press('F3');
      await expectProgram(page, lands);
      await at('back', 'F3 from the selected function returns to the menu.');
      await page.keyboard.press('F3');
      await expectProgram(page, 'CC00 · COSGN00C');
      await expect(page.getByTestId('message')).toHaveText('');
      await page.goto(`/screens/${program}`);
      await expectProgram(page, 'CC00 · COSGN00C');
    },
  };
}

// ---------------------------------------------------------------- accounts

const COACTVW: ScreenWalk = {
  map: 'COACTVW',
  program: 'COACTVWC',
  flowIds: ['NAV-COACTVWC-01', 'NAV-COACTVWC-02', 'NAV-COACTVWC-03'],
  async states(page, at) {
    await user(page);
    await choose(page, '1', 'CAVW · COACTVWC');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await at('initial', 'Main menu option 1.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'No input received');
    await at('error', 'ENTER with no account number: "No input received".');
    await typeInto(page, page.getByLabel('Account Number'), fixtures.linkedAccount);
    await page.keyboard.press('Enter');
    await expect(info(page)).toHaveText('Displaying details of given Account');
    await expect(page.getByTestId('ssn')).toHaveText(/^\*\*\*-\*\*-\d{4}$/);
    await at('populated', `Account ${fixtures.linkedAccount} typed, ENTER: account and customer shown.`);
  },
  async flow(page) {
    await user(page);
    await choose(page, '1', 'CAVW · COACTVWC');
    await expect(info(page)).toHaveText('Enter or update id of account to display');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await page.keyboard.type(fixtures.linkedAccount);
    await page.keyboard.press('F7');
    await expect(info(page)).toHaveText('Displaying details of given Account');
    await expect(page.getByTestId('message')).toHaveText('');
    await ready(page);
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

const COACTUP: ScreenWalk = {
  map: 'COACTUP',
  program: 'COACTUPC',
  flowIds: ['NAV-COACTUPC-01', 'NAV-COACTUPC-02', 'NAV-COACTUPC-03', 'NAV-COACTUPC-04', 'NAV-COACTUPC-07', 'NAV-COACTUPC-08'],
  async states(page, at) {
    await user(page);
    await choose(page, '2', 'CAUP · COACTUPC');
    await expect(page.getByTestId('form')).toHaveAttribute('data-mode', 'SEARCH');
    await at('initial', 'Main menu option 2.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'No input received');
    await at('error', 'ENTER with no account number: "No input received".');
    await typeInto(page, page.getByLabel('Account Number'), UPDATE_ACCOUNT);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('form')).toHaveAttribute('data-mode', 'S');
    await expect(info(page)).toHaveText('Update account details presented above.');
    await at('populated', `Account ${UPDATE_ACCOUNT} typed, ENTER: details fetched for update (state S).`);
  },
  async flow(page, at) {
    const form = page.getByTestId('form');
    const middle = page.getByLabel('Middle Name', { exact: true });
    await user(page);
    await choose(page, '2', 'CAUP · COACTUPC');
    await expect(info(page)).toHaveText('Enter or update id of account to update');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await page.keyboard.type(UPDATE_ACCOUNT);
    await page.keyboard.press('F8');
    await expect(form).toHaveAttribute('data-mode', 'S');
    const before = (await middle.inputValue()).trim();
    const next = before === 'Keyboard' ? 'Tabbed' : 'Keyboard';
    await typeInto(page, middle, next);
    await page.keyboard.press('Enter');
    await expect(form).toHaveAttribute('data-mode', 'N');
    await expect(info(page)).toHaveText('Changes validated.Press F5 to save');
    await at('confirm', `Middle Name changed, ENTER: "Changes validated.Press F5 to save" with F5/F12 shown.`);
    await page.keyboard.press('Enter');
    await ready(page);
    await expect(form).toHaveAttribute('data-mode', 'N');
    await page.keyboard.press('F5');
    await expect(form).toHaveAttribute('data-mode', 'C');
    await expect(info(page)).toHaveText('Changes committed to database');
    await at('result', 'F5 in the confirm state: "Changes committed to database".');
    await page.keyboard.press('Enter');
    await expect(form).toHaveAttribute('data-mode', 'SEARCH');
    await expect(info(page)).toHaveText('Enter or update id of account to update');
    await typeInto(page, page.getByLabel('Account Number'), UPDATE_ACCOUNT);
    await page.keyboard.press('Enter');
    await expect(form).toHaveAttribute('data-mode', 'S');
    await expect(middle).toHaveValue(next);
    await typeInto(page, middle, 'Discarded');
    await page.keyboard.press('Enter');
    await expect(form).toHaveAttribute('data-mode', 'N');
    await page.keyboard.press('F12');
    await expect(form).toHaveAttribute('data-mode', 'S');
    await expect(middle).toHaveValue(next);
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

// ---------------------------------------------------------------- cards

const cardNumbers = (page: Page) => page.getByTestId('card-row').locator('td:nth-child(3)').allTextContents();

const COCRDLI: ScreenWalk = {
  map: 'COCRDLI',
  program: 'COCRDLIC',
  flowIds: [
    'NAV-COCRDLIC-01', 'NAV-COCRDLIC-02', 'NAV-COCRDLIC-03', 'NAV-COCRDLIC-04', 'NAV-COCRDLIC-05', 'NAV-COCRDLIC-06',
    'NAV-COCRDLIC-07', 'NAV-COCRDSLC-02', 'NAV-COCRDUPC-02', 'NAV-COCRDUPC-04',
  ],
  async states(page, at) {
    await user(page);
    await choose(page, '3', 'CCLI · COCRDLIC');
    await expect.poll(() => cardNumbers(page)).toHaveLength(7);
    await expectInfo(page, 'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD');
    await at('initial', 'Main menu option 3: page 1 of the cards in scope.');
    await typeInto(page, page.getByLabel('Account Number'), '12AB');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER');
    await at('error', 'Account filter 12AB, ENTER: "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER".');
    await typeInto(page, page.getByLabel('Account Number'), CARD_ACCOUNT);
    await page.keyboard.press('Enter');
    await expect.poll(() => cardNumbers(page)).toEqual(['************' + CARD.slice(12)]);
    await at('populated', `Account filter ${CARD_ACCOUNT}, ENTER: the filtered list.`);
  },
  async flow(page, at) {
    await user(page);
    await choose(page, '3', 'CCLI · COCRDLIC');
    const first = await cardNumbers(page);
    await page.keyboard.press('F7');
    await expectMessage(page, 'NO PREVIOUS PAGES TO DISPLAY');
    await page.keyboard.press('F8');
    await expect(page.getByTestId('page')).toHaveText('Page 2');
    await at('page2', 'F8 from page 1: page 2 (next page).');
    await page.keyboard.press('F7');
    await expect(page.getByTestId('page')).toHaveText('Page 1');
    await expect.poll(() => cardNumbers(page)).toEqual(first);
    await at('page1', 'F7 from page 2: back to page 1 (previous page).');
    await page.keyboard.press('F2');
    await expectNoInvalidKey(page);
    await expect(page.getByTestId('page')).toHaveText('Page 1');
    await tabTo(page, page.locator('#sel1'));
    await page.keyboard.type('s');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CCDL · COCRDSLC');
    await expect(page.getByLabel('Card Number')).toHaveValue(first[0]);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CCLI · COCRDLIC');
    await expect(page.getByTestId('page')).toHaveText('Page 1');
    await tabTo(page, page.locator('#sel1'));
    await page.keyboard.type('u');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CCUP · COCRDUPC');
    await expectInfo(page, 'Details of selected card shown above');
    await expect(page.getByLabel('Card Number')).toHaveValue(first[0]);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CCLI · COCRDLIC');
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

const COCRDSL: ScreenWalk = {
  map: 'COCRDSL',
  program: 'COCRDSLC',
  flowIds: ['NAV-COCRDSLC-01', 'NAV-COCRDSLC-02', 'NAV-COCRDSLC-04'],
  async states(page, at) {
    await user(page);
    await choose(page, '4', 'CCDL · COCRDSLC');
    await expectInfo(page, 'Please enter Account and Card Number');
    await at('initial', 'Main menu option 4.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'No input received');
    await at('error', 'ENTER with no keys: "No input received".');
    await typeInto(page, page.getByLabel('Account Number'), CARD_ACCOUNT);
    await typeInto(page, page.getByLabel('Card Number'), CARD);
    await page.keyboard.press('Enter');
    await expectInfo(page, '   Displaying requested details');
    await at('populated', `Account ${CARD_ACCOUNT} and its card typed, ENTER: card details shown.`);
  },
  async flow(page) {
    await user(page);
    await choose(page, '4', 'CCDL · COCRDSLC');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await page.keyboard.type(CARD_ACCOUNT);
    await page.keyboard.press('Tab');
    await page.keyboard.type(CARD);
    await page.keyboard.press('Enter');
    await expectInfo(page, '   Displaying requested details');
    await expect(page.getByLabel('Card Number')).toHaveValue('************' + CARD.slice(12));
    await page.keyboard.press('F5');
    await expectNoInvalidKey(page);
    await expectInfo(page, '   Displaying requested details');
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

const COCRDUP: ScreenWalk = {
  map: 'COCRDUP',
  program: 'COCRDUPC',
  flowIds: ['NAV-COCRDUPC-01', 'NAV-COCRDUPC-02', 'NAV-COCRDUPC-05'],
  async states(page, at) {
    await user(page);
    await choose(page, '5', 'CCUP · COCRDUPC');
    await expectInfo(page, 'Please enter Account and Card Number');
    await expect(page.getByLabel('Name on card')).toHaveAttribute('readonly', '');
    await at('initial', 'Main menu option 5.');
    await typeInto(page, page.getByLabel('Account Number'), CARD_ACCOUNT);
    await typeInto(page, page.getByLabel('Card Number'), '1234');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER');
    await at('error', 'Card number 1234, ENTER: "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER".');
    await typeInto(page, page.getByLabel('Card Number'), CARD);
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Details of selected card shown above');
    await at('populated', `Account ${CARD_ACCOUNT} and its card, ENTER: details fetched for update.`);
  },
  async flow(page, at) {
    const name = page.getByLabel('Name on card');
    await user(page);
    await choose(page, '5', 'CCUP · COCRDUPC');
    await expect(page.getByLabel('Account Number')).toBeFocused();
    await page.keyboard.type(CARD_ACCOUNT);
    await page.keyboard.press('Tab');
    await page.keyboard.type(CARD);
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Details of selected card shown above');
    const before = (await name.inputValue()).trim();
    const next = before.endsWith(' A') ? before.slice(0, -2) : `${before} A`;
    await typeInto(page, name, next);
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Changes validated.Press F5 to save');
    await at('confirm', 'Name changed, ENTER: "Changes validated.Press F5 to save" with F5/F12 shown.');
    await page.keyboard.press('F5');
    await expectInfo(page, 'Changes committed to database');
    await at('result', 'F5 in the confirm state: "Changes committed to database".');
    await page.keyboard.press('Enter');
    await expectInfo(page, 'Please enter Account and Card Number');
    await typeInto(page, page.getByLabel('Account Number'), CARD_ACCOUNT);
    await typeInto(page, page.getByLabel('Card Number'), CARD);
    await page.keyboard.press('Enter');
    await expect(name).toHaveValue(new RegExp(`^${next}\\s*$`, 'i'));
    await typeInto(page, name, 'R2D2');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Card name can only contain alphabets and spaces');
    await page.keyboard.press('F12');
    await expectInfo(page, 'Details of selected card shown above');
    await expect(name).not.toHaveValue('R2D2');
    await page.keyboard.press('F7');
    await expectNoInvalidKey(page);
    await expectProgram(page, 'CCUP · COCRDUPC');
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

// ---------------------------------------------------------------- transactions

const tranRows = (page: Page) => page.getByTestId('row');
const firstTranId = (page: Page) => tranRows(page).first().locator('td').nth(1);

const COTRN00: ScreenWalk = {
  map: 'COTRN00',
  program: 'COTRN00C',
  flowIds: [
    'NAV-COTRN00C-02', 'NAV-COTRN00C-03', 'NAV-COTRN00C-04', 'NAV-COTRN00C-05', 'NAV-COTRN00C-06', 'NAV-COTRN00C-07',
    'NAV-COTRN00C-08', 'NAV-COTRN01C-04',
  ],
  async states(page, at) {
    await user(page);
    await choose(page, '6', 'CT00 · COTRN00C');
    await expect(tranRows(page)).toHaveCount(10);
    await at('initial', 'Main menu option 6: page 1 of the transactions in scope.');
    await typeInto(page, page.getByLabel('Search Tran ID'), 'ABC');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Tran ID must be Numeric ...');
    await at('error', 'Search Tran ID ABC, ENTER: "Tran ID must be Numeric ...".');
    const third = (await tranRows(page).nth(2).locator('td').nth(1).textContent()) ?? '';
    await typeInto(page, page.getByLabel('Search Tran ID'), third);
    await page.keyboard.press('Enter');
    await expect(firstTranId(page)).toHaveText(third);
    await at('populated', `Search Tran ID ${third} (third row of page 1), ENTER: the list starts there.`);
  },
  async flow(page, at) {
    await user(page);
    await choose(page, '6', 'CT00 · COTRN00C');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
    await expect(firstTranId(page)).toHaveText(fixtures.userTranId);
    await page.keyboard.press('F7');
    await expectMessage(page, 'You are already at the top of the page...');
    await page.keyboard.press('F8');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000002');
    await at('page2', 'F8 from page 1: page 2 (next page).');
    await page.keyboard.press('F7');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
    await expectMessage(page, 'You have reached the top of the page...');
    await at('page1', 'F7 from page 2: page 1 with "You have reached the top of the page...".');
    await page.keyboard.press('F10');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(tranRows(page)).toHaveCount(10);
    await tabTo(page, page.getByLabel(`Select transaction ${fixtures.userTranId}`));
    await page.keyboard.type('s');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CT01 · COTRN01C');
    await expect(page.getByTestId('tranId')).toHaveText(fixtures.userTranId);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CT00 · COTRN00C');
    await expect(firstTranId(page)).toHaveText(fixtures.userTranId);
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

const COTRN01: ScreenWalk = {
  map: 'COTRN01',
  program: 'COTRN01C',
  flowIds: ['NAV-COTRN01C-02', 'NAV-COTRN01C-03', 'NAV-COTRN01C-04', 'NAV-COTRN01C-05', 'NAV-COTRN01C-06', 'NAV-COTRN01C-07'],
  async states(page, at) {
    await user(page);
    await choose(page, '7', 'CT01 · COTRN01C');
    await expect(page.getByLabel('Enter Tran ID')).toBeFocused();
    await at('initial', 'Main menu option 7.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Tran ID can NOT be empty...');
    await at('error', 'ENTER with no ID: "Tran ID can NOT be empty...".');
    await typeInto(page, page.getByLabel('Enter Tran ID'), fixtures.userTranId);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('tranId')).toHaveText(fixtures.userTranId);
    await at('populated', `Tran ID ${fixtures.userTranId}, ENTER: the transaction shown.`);
  },
  async flow(page) {
    await user(page);
    await choose(page, '7', 'CT01 · COTRN01C');
    await expect(page.getByLabel('Enter Tran ID')).toBeFocused();
    await expect(page.getByLabel('Enter Tran ID')).toHaveValue('');
    await page.keyboard.type(fixtures.userTranId);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('tranId')).toHaveText(fixtures.userTranId);
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter Tran ID')).toHaveValue('');
    await expect(page.getByTestId('tranId')).toHaveText('—');
    await page.keyboard.press('F7');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F5');
    await expectProgram(page, 'CT00 · COTRN00C');
    await expect(page.getByTestId('page')).toHaveAttribute('data-raw', '00000001');
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
    await choose(page, '7', 'CT01 · COTRN01C');
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

const TRAN: [string, string][] = [
  ['Type CD', '01'],
  ['Category CD', '0001'],
  ['Source', 'POS TERM'],
  ['Description', 'Keyboard purchase'],
  ['Amount', '+00000007.50'],
  ['Orig Date', '2024-02-15'],
  ['Proc Date', '2024-02-16'],
  ['Merchant ID', '000000002'],
  ['Merchant Name', 'Keyboard Store'],
  ['Merchant City', 'Seattle'],
  ['Merchant Zip', '98101'],
];

async function typeTransaction(page: Page) {
  await typeInto(page, page.getByLabel('Enter Acct #'), PAY_ACCOUNT);
  for (const [label, value] of TRAN) await typeInto(page, page.getByLabel(label, { exact: true }), value);
}

const COTRN02: ScreenWalk = {
  map: 'COTRN02',
  program: 'COTRN02C',
  flowIds: ['NAV-COTRN02C-02', 'NAV-COTRN02C-03', 'NAV-COTRN02C-04', 'NAV-COTRN02C-05', 'NAV-COTRN02C-06', 'NAV-COTRN02C-07'],
  async states(page, at) {
    await user(page);
    await choose(page, '8', 'CT02 · COTRN02C');
    await expect(page.getByLabel('Enter Acct #')).toBeFocused();
    await at('initial', 'Main menu option 8.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Account or Card Number must be entered...');
    await at('error', 'ENTER with no keys: "Account or Card Number must be entered...".');
    await typeTransaction(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Confirm to add this transaction...');
    await expect(page.getByLabel(/Please confirm/)).toBeFocused();
    await at('populated', `Account ${PAY_ACCOUNT} and every data field typed, ENTER: "Confirm to add this transaction...".`);
  },
  async flow(page, at) {
    await user(page);
    await choose(page, '8', 'CT02 · COTRN02C');
    await expect(page.getByLabel('Enter Acct #')).toBeFocused();
    await typeTransaction(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Confirm to add this transaction...');
    await page.keyboard.type('y');
    await page.keyboard.press('Enter');
    await expect(msgText(page)).toHaveText(/^Transaction added successfully\. {2}Your Tran ID is \d{16}\.$/);
    await at('result', 'Confirm Y, ENTER: "Transaction added successfully.  Your Tran ID is ...".');
    await typeInto(page, page.getByLabel('Enter Acct #'), PAY_ACCOUNT);
    await page.keyboard.press('F5');
    await expectMessage(page, 'Confirm to add this transaction...');
    await expect(page.getByLabel('Description', { exact: true })).toHaveValue('Keyboard purchase');
    await at('copy', 'Account typed, F5: the last transaction copied for review.');
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter Acct #')).toHaveValue('');
    await expect(page.getByLabel('Description', { exact: true })).toHaveValue('');
    await page.keyboard.press('F9');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

// ---------------------------------------------------------------- reports and bill payment

const reportField = (page: Page, date: 'start-date' | 'end-date', part: 'Month' | 'Day' | 'Year') =>
  page.getByTestId(date).getByLabel(part, { exact: true });

const CORPT00: ScreenWalk = {
  map: 'CORPT00',
  program: 'CORPT00C',
  flowIds: ['NAV-CORPT00C-02', 'NAV-CORPT00C-03', 'NAV-CORPT00C-04', 'NAV-CORPT00C-05'],
  async states(page, at) {
    await user(page);
    await choose(page, '9', 'CR00 · CORPT00C');
    await expect(page.getByLabel(/^Monthly/)).toBeFocused();
    await at('initial', 'Main menu option 9.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Select a report type to print report...');
    await at('error', 'ENTER with no report type: "Select a report type to print report...".');
    await expect(page.getByLabel(/^Monthly/)).toBeFocused();
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowRight');
    await expect(page.getByLabel(/^Custom/)).toBeChecked();
    for (const [date, values] of [['start-date', ['6', '1', '2022']], ['end-date', ['06', '30', '2022']]] as const) {
      for (const [i, part] of (['Month', 'Day', 'Year'] as const).entries()) await typeInto(page, reportField(page, date, part), values[i]);
    }
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Please confirm to print the Custom report...');
    await expect(reportField(page, 'start-date', 'Month')).toHaveValue('06');
    await at('populated', 'Custom (arrow keys) with 6/1/2022-06/30/2022, ENTER: dates normalized, "Please confirm to print the Custom report...".');
  },
  async flow(page, at) {
    await user(page);
    await choose(page, '9', 'CR00 · CORPT00C');
    const monthly = page.getByLabel(/^Monthly/);
    await expect(monthly).toBeFocused();
    await page.keyboard.press('Space');
    await expect(monthly).toBeChecked();
    await tabTo(page, page.getByLabel(/Please confirm/));
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Please confirm to print the Monthly report...');
    await at('confirm', 'Monthly (Space), ENTER: "Please confirm to print the Monthly report...".');
    await page.keyboard.type('y');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Monthly report submitted for printing ...');
    await expect(page.getByTestId('run').first()).toBeVisible();
    await at('result', 'Confirm Y, ENTER: "Monthly report submitted for printing ..." and the run listed.');
    await page.keyboard.press('F4');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

const COBIL00: ScreenWalk = {
  map: 'COBIL00',
  program: 'COBIL00C',
  flowIds: ['NAV-COBIL00C-02', 'NAV-COBIL00C-03', 'NAV-COBIL00C-04', 'NAV-COBIL00C-05', 'NAV-COBIL00C-06'],
  async states(page, at) {
    await user(page);
    await choose(page, '10', 'CB00 · COBIL00C');
    await expect(page.getByLabel('Enter Acct ID')).toBeFocused();
    await at('initial', 'Main menu option 10.');
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BIL_EMPTY);
    await at('error', 'ENTER with no account: "Acct ID can NOT be empty...".');
    await typeInto(page, page.getByLabel('Enter Acct ID'), PREVIEW_ACCOUNT);
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BIL_CONFIRM);
    await expect(page.getByTestId('balance')).not.toHaveText('—');
    await at('populated', `Account ${PREVIEW_ACCOUNT}, ENTER: balance shown with "Confirm to make a bill payment...".`);
  },
  async flow(page, at) {
    await user(page);
    await choose(page, '10', 'CB00 · COBIL00C');
    await expect(page.getByLabel('Enter Acct ID')).toBeFocused();
    await page.keyboard.type(PAY_ACCOUNT);
    await page.keyboard.press('Enter');
    await expectMessage(page, MSG.BIL_CONFIRM);
    await expect(page.getByLabel(/Please confirm/)).toBeFocused();
    await at('confirm', `Account ${PAY_ACCOUNT}, ENTER: "Confirm to make a bill payment...".`);
    await page.keyboard.type('y');
    await page.keyboard.press('Enter');
    await expect(msgText(page)).toHaveText(/^Payment successful\. {2}Your Transaction ID is \d{16}\.$/);
    await expect(page.getByTestId('receipt')).toContainText(PAY_ACCOUNT);
    await at('result', 'Confirm Y, ENTER: "Payment successful..." and the receipt.');
    await typeInto(page, page.getByLabel('Enter Acct ID'), PREVIEW_ACCOUNT);
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter Acct ID')).toHaveValue('');
    await expect(page.getByTestId('message')).toHaveText('');
    await page.keyboard.type(PREVIEW_ACCOUNT);
    await page.keyboard.press('F5');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByLabel('Enter Acct ID')).toHaveValue(PREVIEW_ACCOUNT);
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

// ---------------------------------------------------------------- users

const userIds = (page: Page) => page.getByTestId('user-row').locator('td:nth-child(2)').allTextContents();

const COUSR00: ScreenWalk = {
  map: 'COUSR00',
  program: 'COUSR00C',
  flowIds: ['NAV-COUSR00C-02', 'NAV-COUSR00C-03', 'NAV-COUSR00C-04', 'NAV-COUSR00C-05', 'NAV-COUSR00C-06', 'NAV-COUSR02C-02', 'NAV-COUSR03C-02'],
  async states(page, at) {
    await admin(page);
    await choose(page, '1', 'CU00 · COUSR00C');
    await expect.poll(() => userIds(page)).toHaveLength(10);
    await at('initial', 'Admin menu option 1: page 1 of the users.');
    await tabTo(page, page.getByLabel('Select user ADMIN003'));
    await page.keyboard.type('x');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Invalid selection. Valid values are U and D');
    await at('error', 'Selection code X on ADMIN003, ENTER: "Invalid selection. Valid values are U and D".');
    await typeInto(page, page.getByLabel('Search User ID'), 'user0003', true);
    await page.keyboard.press('Enter');
    await expect.poll(() => userIds(page)).toEqual(['USER0003', 'USER0004', 'USER0005']);
    await at('populated', 'Search User ID USER0003, ENTER: the list starts there.');
  },
  async flow(page) {
    await admin(page);
    await choose(page, '1', 'CU00 · COUSR00C');
    await page.keyboard.press('F8');
    await expectMessage(page, 'You are already at the bottom of the page...');
    await page.keyboard.press('F7');
    await expectMessage(page, 'You are already at the top of the page...');
    await page.keyboard.press('F5');
    await expectMessage(page, MSG.INVALID_KEY);
    await tabTo(page, page.getByLabel('Select user ADMIN002'));
    await page.keyboard.type('u');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU02 · COUSR02C');
    await expect(page.getByLabel('Enter User ID')).toHaveValue('ADMIN002');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CU00 · COUSR00C');
    await tabTo(page, page.getByLabel('Select user USER0001'));
    await page.keyboard.type('d');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU03 · COUSR03C');
    await expect(page.getByLabel('Enter User ID')).toHaveValue('USER0001');
    await expectMessage(page, 'Press PF5 key to delete this user ...');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CU00 · COUSR00C');
    await page.keyboard.press('F3');
    await expectProgram(page, ADMIN);
  },
};

const NEW_USER_FIELDS: [string, string][] = [
  ['First Name', 'Keyboard'],
  ['Last Name', 'Tester'],
  ['User ID', A11Y_USER.toLowerCase()],
  ['Password', 'Secret12'],
  ['User Type', 'u'],
];

async function typeNewUser(page: Page) {
  for (const [label, value] of NEW_USER_FIELDS) await typeInto(page, page.getByLabel(label, { exact: true }), value);
}

const COUSR01: ScreenWalk = {
  map: 'COUSR01',
  program: 'COUSR01C',
  flowIds: ['NAV-COUSR01C-01', 'NAV-COUSR01C-02', 'NAV-COUSR01C-03', 'NAV-COUSR01C-04', 'Q-COUSR01C-05'],
  async states(page, at) {
    await admin(page);
    await choose(page, '2', 'CU01 · COUSR01C');
    await expect(page.getByLabel('First Name')).toBeFocused();
    await at('initial', 'Admin menu option 2.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'First Name can NOT be empty...');
    await at('error', 'ENTER with every field empty: "First Name can NOT be empty...".');
    await typeNewUser(page);
    await expect(page.getByLabel('User ID')).toHaveValue(A11Y_USER);
    await at('populated', 'Closest real state (an add screen shows no stored record): every field typed, before ENTER.');
    await page.keyboard.press('F4');
    await expect(page.getByLabel('First Name')).toHaveValue('');
  },
  async flow(page, at) {
    await admin(page);
    await choose(page, '2', 'CU01 · COUSR01C');
    await typeNewUser(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, `User ${A11Y_USER} has been added ...`);
    await expect(page.getByLabel('First Name')).toBeFocused();
    await at('result', `Every field typed, ENTER: "User ${A11Y_USER} has been added ...".`);
    await page.keyboard.type('NOTSAVED');
    await page.keyboard.press('F5');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByLabel('First Name')).toHaveValue('NOTSAVED');
    await page.keyboard.press('F4');
    await expect(page.getByLabel('First Name')).toHaveValue('');
    await expect(page.getByTestId('message')).toHaveText('');
    await page.keyboard.press('F12');
    await expectProgram(page, ADMIN);
    await choose(page, '2', 'CU01 · COUSR01C');
    await page.keyboard.press('F3');
    await expectProgram(page, ADMIN);
  },
};

const COUSR02: ScreenWalk = {
  map: 'COUSR02',
  program: 'COUSR02C',
  flowIds: ['NAV-COUSR02C-01', 'NAV-COUSR02C-02', 'NAV-COUSR02C-03', 'NAV-COUSR02C-04', 'NAV-COUSR02C-05', 'NAV-COUSR02C-06'],
  async states(page, at) {
    await admin(page);
    await choose(page, '3', 'CU02 · COUSR02C');
    await expect(page.getByLabel('Enter User ID')).toBeFocused();
    await at('initial', 'Admin menu option 3.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'User ID can NOT be empty...');
    await at('error', 'ENTER with no User ID: "User ID can NOT be empty...".');
    await typeInto(page, page.getByLabel('Enter User ID'), 'USER0005');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Press PF5 key to save your updates ...');
    await expect(page.getByLabel('Last Name')).not.toHaveValue('');
    await at('populated', 'User ID USER0005, ENTER: the user fetched for update.');
  },
  async flow(page) {
    const last = page.getByLabel('Last Name');
    await admin(page);
    await choose(page, '3', 'CU02 · COUSR02C');
    await page.keyboard.type(A11Y_USER);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Press PF5 key to save your updates ...');
    await expect(page.getByLabel('First Name')).toBeFocused();
    await typeInto(page, last, 'Changed');
    await page.keyboard.press('F5');
    await expectMessage(page, `User ${A11Y_USER} has been updated ...`);
    await page.keyboard.press('F7');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(last).toHaveValue('Changed');
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter User ID')).toHaveValue('');
    await expect(last).toHaveValue('');
    await typeInto(page, page.getByLabel('Enter User ID'), A11Y_USER, true);
    await page.keyboard.press('Enter');
    await expect(last).toHaveValue('Changed');
    await typeInto(page, last, 'Discarded');
    await page.keyboard.press('F12');
    await expectProgram(page, ADMIN);
    await choose(page, '3', 'CU02 · COUSR02C');
    await page.keyboard.type(A11Y_USER);
    await page.keyboard.press('Enter');
    await expect(last).toHaveValue('Changed');
    await page.keyboard.press('F3');
    await expectProgram(page, ADMIN);
  },
};

const COUSR03: ScreenWalk = {
  map: 'COUSR03',
  program: 'COUSR03C',
  flowIds: ['NAV-COUSR03C-01', 'NAV-COUSR03C-02', 'NAV-COUSR03C-03', 'NAV-COUSR03C-04', 'NAV-COUSR03C-05', 'NAV-COUSR03C-06'],
  async states(page, at) {
    await admin(page);
    await choose(page, '4', 'CU03 · COUSR03C');
    await expect(page.getByLabel('Enter User ID')).toBeFocused();
    await at('initial', 'Admin menu option 4.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'User ID can NOT be empty...');
    await at('error', 'ENTER with no User ID: "User ID can NOT be empty...".');
    await typeInto(page, page.getByLabel('Enter User ID'), 'USER0005');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Press PF5 key to delete this user ...');
    await at('populated', 'User ID USER0005, ENTER: the user shown for deletion (not deleted).');
  },
  async flow(page, at) {
    const details = page.getByTestId('user-details');
    await admin(page);
    await choose(page, '4', 'CU03 · COUSR03C');
    await page.keyboard.type(A11Y_USER);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Press PF5 key to delete this user ...');
    await expect(details).toContainText('Changed');
    await page.keyboard.press('F8');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter User ID')).toHaveValue('');
    await expect(details).not.toContainText('Changed');
    await page.keyboard.press('F12');
    await expectProgram(page, ADMIN);
    await choose(page, '4', 'CU03 · COUSR03C');
    await page.keyboard.type(A11Y_USER);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Press PF5 key to delete this user ...');
    await page.keyboard.press('F5');
    await expectMessage(page, `User ${A11Y_USER} has been deleted ...`);
    await at('result', `F5 after the fetch: "User ${A11Y_USER} has been deleted ...".`);
    await page.keyboard.press('F3');
    await expectProgram(page, ADMIN);
  },
};

// ---------------------------------------------------------------- pending authorizations

const authTxns = (page: Page) => page.getByTestId('auth-row').locator('td:nth-child(2)').allTextContents();

async function authList(page: Page) {
  await signOnKb(page, 'USER0002', MAIN);
  await choose(page, '11', 'CPVS · COPAUS0C');
}

async function searchAuth(page: Page) {
  await typeInto(page, page.getByLabel('Search Acct Id'), AUTH_ACCOUNT);
  await page.keyboard.press('Enter');
  await expect.poll(() => authTxns(page)).toHaveLength(5);
}

const COPAU00: ScreenWalk = {
  map: 'COPAU00',
  program: 'COPAUS0C',
  flowIds: ['NAV-COPAUS0C-03', 'NAV-COPAUS0C-04', 'NAV-COPAUS0C-05', 'NAV-COPAUS0C-06', 'NAV-COPAUS0C-07', 'NAV-COPAUS0C-08', 'NAV-COPAUS1C-04'],
  async states(page, at) {
    await authList(page);
    await expect(page.getByLabel('Search Acct Id')).toBeFocused();
    await at('initial', 'USER0002, main menu option 11.');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Please enter Acct Id...');
    await at('error', 'ENTER with no account: "Please enter Acct Id...".');
    await searchAuth(page);
    await at('populated', `Account ${AUTH_ACCOUNT}, ENTER: summary and the 5 newest authorizations.`);
  },
  async flow(page, at) {
    await authList(page);
    await searchAuth(page);
    const page1 = await authTxns(page);
    await page.keyboard.press('F7');
    await expectMessage(page, 'You are already at the top of the page...');
    await page.keyboard.press('F8');
    await expect.poll(async () => (await authTxns(page))[0]).not.toBe(page1[0]);
    await at('page2', 'F8 from page 1: page 2 (next page).');
    await page.keyboard.press('F7');
    await expect.poll(() => authTxns(page)).toEqual(page1);
    await page.keyboard.press('F2');
    await expectMessage(page, MSG.INVALID_KEY);
    await tabTo(page, page.locator('#sel3'));
    await page.keyboard.type('s');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CPVD · COPAUS1C');
    await expect(page.getByTestId('transaction-id')).toHaveText(page1[2]);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CPVS · COPAUS0C');
    await expect(page.getByLabel('Search Acct Id')).toHaveValue(AUTH_ACCOUNT);
    await expect.poll(async () => (await authTxns(page))[0]).toBe(page1[0]);
    await page.keyboard.press('F3');
    await expectProgram(page, MAIN);
  },
};

async function openDetail(page: Page, row: number) {
  await authList(page);
  await searchAuth(page);
  const txns = await authTxns(page);
  await tabTo(page, page.locator(`#sel${row}`));
  await page.keyboard.type('s');
  await page.keyboard.press('Enter');
  await expectProgram(page, 'CPVD · COPAUS1C');
  await expect(page.getByTestId('transaction-id')).toHaveText(txns[row - 1]);
  return txns;
}

const COPAU01: ScreenWalk = {
  map: 'COPAU01',
  program: 'COPAUS1C',
  flowIds: ['NAV-COPAUS1C-02', 'NAV-COPAUS1C-03', 'NAV-COPAUS1C-04', 'NAV-COPAUS1C-05', 'NAV-COPAUS1C-06', 'NAV-COPAUS1C-07'],
  async states(page, at) {
    const txns = await openDetail(page, 1);
    await at('initial', `USER0002, option 11, account ${AUTH_ACCOUNT}, S on row 1: the detail on first entry.`);
    await page.keyboard.press('F7');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByTestId('transaction-id')).toHaveText(txns[0]);
    await at('error', 'Closest real state (COPAU01 has no input field to validate): F7, "Invalid key pressed" over the detail.');
    await page.keyboard.press('F8');
    await expect(page.getByTestId('transaction-id')).toHaveText(txns[1]);
    await at('populated', 'F8: the next (older) authorization.');
  },
  async flow(page, at) {
    const txns = await openDetail(page, 3);
    // ENTER re-reads the detail: it must reach the server from the cursor field, not only from a button.
    const turn = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().endsWith('/api/screens/COPAUS1C') && r.postDataJSON()?.key === 'ENTER',
      { timeout: 5_000 },
    );
    await page.keyboard.press('Enter');
    await turn;
    await ready(page);
    await expect(page.getByTestId('transaction-id')).toHaveText(txns[2]);
    await expect(page.getByTestId('message')).toHaveText('');
    await page.keyboard.press('F5');
    await expectMessage(page, 'AUTH MARKED FRAUD...');
    await expect(page.getByTestId('fraud-status')).toHaveText(/^F-\d\d\/\d\d\/\d\d$/);
    await at('result', 'F5: "AUTH MARKED FRAUD..." with the fraud status.');
    await page.keyboard.press('F5');
    await expectMessage(page, 'AUTH FRAUD REMOVED...');
    await page.keyboard.press('F8');
    await expect(page.getByTestId('transaction-id')).toHaveText(txns[3]);
    await page.keyboard.press('F7');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CPVS · COPAUS0C');
  },
};

// ---------------------------------------------------------------- transaction types

const LIST = 'CTLI · COTRTLIC';
const MAINT = 'CTTU · COTRTUPC';
const legend = (page: Page) => page.getByRole('navigation', { name: 'Function keys' }).locator('kbd');

const COTRTLI: ScreenWalk = {
  map: 'COTRTLI',
  program: 'COTRTLIC',
  flowIds: ['NAV-COTRTLIC-01', 'NAV-COTRTLIC-02', 'NAV-COTRTLIC-03', 'NAV-COTRTLIC-04', 'NAV-COTRTLIC-05', 'NAV-COTRTUPC-02'],
  async states(page, at) {
    await admin(page);
    await choose(page, '5', LIST);
    await expect(page.getByLabel('Type Filter')).toBeFocused();
    await expect(info(page)).toHaveText('Type U to update, D to delete any record');
    await at('initial', 'Admin menu option 5: page 1 of the transaction types.');
    await page.keyboard.type('AB');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER');
    await at('error', 'Type Filter AB, ENTER: "TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER".');
    await typeInto(page, page.getByLabel('Type Filter'), '03');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('row1').locator('td').nth(1)).toHaveText('03');
    await at('populated', 'Type Filter 03, ENTER: the filtered list.');
  },
  async flow(page, at) {
    const desc3 = page.getByTestId('row3').locator('td').nth(2);
    await admin(page);
    await choose(page, '5', LIST);
    await page.keyboard.press('F7');
    await expectMessage(page, 'No previous pages to display');
    // The seven sample types fill exactly one page.
    await page.keyboard.press('F8');
    await expect(msgText(page)).toHaveText(/^No more pages (to display|for these search conditions)$/);
    await expect(page.getByTestId('page')).toHaveText('1');
    await page.keyboard.press('F5');
    await expectNoInvalidKey(page);
    await expect(page.getByTestId('page')).toHaveText('1');
    const original = ((await desc3.textContent()) ?? '').trim();
    const next = original === 'Keyboard edit' ? 'Keyboard edit again' : 'Keyboard edit';
    for (const value of [next, original]) {
      await tabTo(page, page.getByLabel('Select row 3'));
      await page.keyboard.type('u');
      await page.keyboard.press('Enter');
      await expect(info(page)).toHaveText('Update HIGHLIGHTED row. Press F10 to save');
      await expect(page.getByLabel('Description row 3')).toBeFocused();
      if (value === next) await at('confirm', 'U on row 3, ENTER: the row opened for update.');
      await page.keyboard.press('ControlOrMeta+a');
      await page.keyboard.type(value);
      await page.keyboard.press('F10');
      await expect(info(page)).toHaveText('HIGHLIGHTED row was updated');
      await expect(desc3).toHaveText(value);
      if (value === next) await at('result', 'Description changed, F10: "HIGHLIGHTED row was updated".');
    }
    await page.keyboard.press('F2');
    await expectProgram(page, MAINT);
    await page.keyboard.press('F3');
    await expectProgram(page, LIST);
    await expect(page.getByTestId('page')).toHaveText('1');
    await page.keyboard.press('F3');
    await expectProgram(page, ADMIN);
  },
};

const COTRTUP: ScreenWalk = {
  map: 'COTRTUP',
  program: 'COTRTUPC',
  flowIds: ['NAV-COTRTUPC-01', 'NAV-COTRTUPC-02', 'NAV-COTRTUPC-03', 'NAV-COTRTUPC-04', 'NAV-COTRTUPC-05', 'NAV-COTRTUPC-06'],
  async states(page, at) {
    await admin(page);
    await choose(page, '6', MAINT);
    await expect(info(page)).toHaveText('Enter transaction type to be maintained');
    await expect(legend(page)).toHaveText(['ENTER', 'F3']);
    await at('initial', 'Admin menu option 6.');
    await page.keyboard.type('A1');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Tran Type code must be numeric.');
    await at('error', 'Transaction Type A1, ENTER: "Tran Type code must be numeric.".');
    await typeInto(page, page.getByLabel('Transaction Type'), '01');
    await page.keyboard.press('Enter');
    await expect(info(page)).toHaveText('Update transaction type details shown.');
    await expect(page.getByLabel('Description')).not.toHaveValue('');
    await at('populated', 'Transaction Type 01, ENTER: the type shown for update.');
  },
  async flow(page, at) {
    const type = page.getByLabel('Transaction Type');
    await admin(page);
    await choose(page, '6', MAINT);
    await page.keyboard.type(NEW_TYPE);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'No record found for this key in database');
    await expect(info(page)).toHaveText('Press F05 to add. F12 to cancel');
    await at('notfound', `Type ${NEW_TYPE}, ENTER: not found, "Press F05 to add. F12 to cancel".`);
    await page.keyboard.press('F12');
    await expect(info(page)).toHaveText('Enter transaction type to be maintained');
    await typeInto(page, type, NEW_TYPE);
    await page.keyboard.press('Enter');
    await expect(info(page)).toHaveText('Press F05 to add. F12 to cancel');
    await page.keyboard.press('F5');
    await expect(info(page)).toHaveText('Enter new transaction type details.');
    await expect(page.getByLabel('Description')).toBeFocused();
    await page.keyboard.type('Keyboard type');
    await page.keyboard.press('Enter');
    await expect(info(page)).toHaveText('Changes validated.Press F5 to save');
    await at('confirm', 'F5 (add), description typed, ENTER: "Changes validated.Press F5 to save".');
    await page.keyboard.press('F5');
    await expect(info(page)).toHaveText('Changes committed to database');
    await at('result', 'F5: "Changes committed to database".');
    await page.keyboard.press('Enter');
    await expect(info(page)).toHaveText('Enter transaction type to be maintained');
    await typeInto(page, type, NEW_TYPE);
    await page.keyboard.press('Enter');
    await expect(info(page)).toHaveText('Update transaction type details shown.');
    await page.keyboard.press('F7');
    await expect(msgText(page)).toHaveText(/^Invalid key pressed/);
    await expect(info(page)).toHaveText('Update transaction type details shown.');
    await page.keyboard.press('F4');
    await expect(info(page)).toHaveText('Delete this record ? Press F4 to confirm');
    await expect(legend(page)).toHaveText(['F4', 'F12']);
    await at('delete', 'F4 on the shown type: "Delete this record ? Press F4 to confirm".');
    await page.keyboard.press('F12');
    await expectMessage(page, 'Delete was cancelled');
    await typeInto(page, type, NEW_TYPE);
    await page.keyboard.press('Enter');
    await expect(info(page)).toHaveText('Update transaction type details shown.');
    await page.keyboard.press('F4');
    await expect(info(page)).toHaveText('Delete this record ? Press F4 to confirm');
    await page.keyboard.press('F4');
    await expect(info(page)).toHaveText('Delete successful.');
    await page.keyboard.press('F3');
    await expectProgram(page, ADMIN);
  },
};

/** One walk per BMS map, in the order the suites run them (the user walks depend on each other). */
export const WALKS: ScreenWalk[] = [
  COSGN00,
  menu('COMEN01', 'COMEN01C'),
  menu('COADM01', 'COADM01C'),
  COACTVW,
  COACTUP,
  COCRDLI,
  COCRDSL,
  COCRDUP,
  COTRN00,
  COTRN01,
  COTRN02,
  CORPT00,
  COBIL00,
  COUSR00,
  COUSR01,
  COUSR02,
  COUSR03,
  COPAU00,
  COPAU01,
  COTRTLI,
  COTRTUP,
];
