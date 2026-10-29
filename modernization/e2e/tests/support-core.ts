import AxeBuilder from '@axe-core/playwright';
import { type Page, expect } from '@playwright/test';

/** Public demo password documented in the repository README. */
export const DEMO_PASSWORD = 'PASSWORD';

/** Verbatim message texts (trailing spaces included) from the COBOL programs. */
export const MSG = {
  THANK_YOU: 'Thank you for using CardDemo application...      ',
  INVALID_KEY: 'Invalid key pressed. Please see below...         ',
  ADMIN_ONLY: 'No access - Admin Only option... ',
  NO_USER: 'Please enter User ID ...',
  NO_PASSWORD: 'Please enter Password ...',
  BAD_CREDENTIALS: 'Invalid User ID or Password. Try again ...',
  BAD_OPTION: 'Please enter a valid option number...',
  BIL_EMPTY: 'Acct ID can NOT be empty...',
  BIL_NOT_FOUND: 'Account ID NOT found...',
  BIL_CONFIRM: 'Confirm to make a bill payment...',
  BIL_YN: 'Invalid value. Valid values are (Y/N)...',
};

/** Exact message-line text, without whitespace normalization. */
export async function expectMessage(page: Page, text: string) {
  await expect.poll(() => page.getByTestId('message').locator('.msg-text').textContent()).toBe(text);
}

/** The screen shown is this transaction/program and has finished loading (keys are accepted). */
export async function expectProgram(page: Page, tranAndProgram: string) {
  await expect(page.getByTestId('tran')).toHaveText(tranAndProgram);
  await expect(page.locator('main[data-ready]')).toBeVisible();
}

export async function open(page: Page, path: string, tranAndProgram: string) {
  await page.goto(path);
  await expectProgram(page, tranAndProgram);
}

export async function signOn(page: Page, userId: string, password = DEMO_PASSWORD) {
  await open(page, '/screens/COSGN00C', 'CC00 · COSGN00C');
  await page.getByLabel('User ID').fill(userId);
  await page.getByLabel('Password').fill(password);
  await page.keyboard.press('Enter');
}

export async function expectNoSeriousA11yViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  const serious = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(serious.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}
