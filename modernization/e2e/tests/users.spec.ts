import { type Page, expect, test } from '@playwright/test';
import { MSG, expectMessage, expectNoSeriousA11yViolations, expectProgram, signOn } from './support';

/** A user created and removed by these tests; not in the sample USRSEC. */
const NEW_USER = 'E2EUSR01';

async function adminMenu(page: Page) {
  await signOn(page, 'ADMIN001');
  await expectProgram(page, 'CA00 · COADM01C');
}

async function openOption(page: Page, name: RegExp, tranAndProgram: string) {
  await adminMenu(page);
  await page.getByRole('button', { name }).click();
  await expectProgram(page, tranAndProgram);
}

function userIds(page: Page) {
  return page.getByTestId('user-row').locator('td:nth-child(2)').allTextContents();
}

const SAMPLE = ['ADMIN001', 'ADMIN002', 'ADMIN003', 'ADMIN004', 'ADMIN005', 'USER0001', 'USER0002', 'USER0003', 'USER0004', 'USER0005'];

test.describe.configure({ mode: 'serial' });

test('BR-COUSR00C-15 BR-COUSR01C-06 BR-COUSR02C-07 BR-COUSR03C-06 each user screen shows its transaction, program, date, and time', async ({ page }) => {
  await adminMenu(page);
  for (const [option, header] of [
    ['1', 'CU00 · COUSR00C'],
    ['2', 'CU01 · COUSR01C'],
    ['3', 'CU02 · COUSR02C'],
    ['4', 'CU03 · COUSR03C'],
  ]) {
    await page.goto('/screens/COADM01C');
    await expectProgram(page, 'CA00 · COADM01C');
    await page.getByLabel('Please select an option').fill(option);
    await page.keyboard.press('Enter');
    await expectProgram(page, header);
    await expect(page.getByLabel('Date and time')).toHaveText(/^\d\d\/\d\d\/\d\d {2}\d\d:\d\d:\d\d$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(['List Users', 'Add User', 'Update User', 'Delete User'][Number(option) - 1]);
  }
});

test.describe('COUSR00C user list', () => {
  test('TS-COUSR00C-01 BR-COUSR00C-02 first entry lists the ten sample users on page 1', async ({ page }) => {
    await openOption(page, /User List/, 'CU00 · COUSR00C');
    expect(await userIds(page)).toEqual(SAMPLE);
    await expect(page.getByTestId('page')).toHaveText('Page 00000001');
    await expectMessage(page, 'You have reached the bottom of the page...');
    await expect(page.getByLabel('Search User ID')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('VR-COUSR00C-01 Q-COUSR00C-06 an invalid selection code is rejected and the list rebuilt with cleared codes', async ({ page }) => {
    await openOption(page, /User List/, 'CU00 · COUSR00C');
    await page.getByLabel('Select user ADMIN003').fill('x');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Invalid selection. Valid values are U and D');
    // The cursor goes to the search field, but the message is not about its value (UIR-8).
    await expect(page.getByLabel('Search User ID')).not.toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Select user ADMIN003')).toHaveValue('');
    expect(await userIds(page)).toEqual(SAMPLE);
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COUSR00C-04 search starts at the typed user ID and clears the search field', async ({ page }) => {
    await openOption(page, /User List/, 'CU00 · COUSR00C');
    await page.keyboard.type('user0003');
    await page.keyboard.press('Enter');
    await expect.poll(() => userIds(page)).toEqual(['USER0003', 'USER0004', 'USER0005']);
    await expect(page.getByLabel('Search User ID')).toHaveValue('');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COUSR00C-05 TS-COUSR00C-06 NAV-COUSR00C-06 keyboard: U opens update, D opens delete, F3 returns to the list', async ({ page }) => {
    await adminMenu(page);
    await page.keyboard.type('1');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU00 · COUSR00C');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('Select user ADMIN001')).toBeFocused();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('Select user ADMIN002')).toBeFocused();
    await page.keyboard.type('u');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU02 · COUSR02C');
    await expect(page.getByLabel('Enter User ID')).toHaveValue('ADMIN002');
    await expectMessage(page, 'Press PF5 key to save your updates ...');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CU00 · COUSR00C');
    await page.getByLabel('Select user USER0001').fill('d');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU03 · COUSR03C');
    await expect(page.getByLabel('Enter User ID')).toHaveValue('USER0001');
    await expectMessage(page, 'Press PF5 key to delete this user ...');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CU00 · COUSR00C');
  });

  test('TS-COUSR00C-02 TS-COUSR00C-03 TS-COUSR00C-12 TS-COUSR00C-13 F8, F7, other keys, and F3', async ({ page }) => {
    await openOption(page, /User List/, 'CU00 · COUSR00C');
    await page.keyboard.press('F8');
    await expectMessage(page, 'You are already at the bottom of the page...');
    await page.keyboard.press('F7');
    await expectMessage(page, 'You are already at the top of the page...');
    expect(await userIds(page)).toEqual(SAMPLE);
    await page.keyboard.press('F5');
    await expectMessage(page, MSG.INVALID_KEY);
    await page.keyboard.press('F3');
    await expectProgram(page, 'CA00 · COADM01C');
  });

  test('Q-COUSR00C-15 TS-COUSR00C-17 F8 after F7 on page 1 stays on the last page with the rows kept', async ({ page }) => {
    await openOption(page, /User List/, 'CU00 · COUSR00C');
    await page.keyboard.press('F7');
    await expectMessage(page, 'You are already at the top of the page...');
    await page.keyboard.press('F8');
    await expectMessage(page, 'You are already at the bottom of the page...');
    expect(await userIds(page)).toEqual(SAMPLE);
    await expect(page.getByTestId('page')).toHaveText('Page 00000001');
    await expectNoSeriousA11yViolations(page);
  });
});

test.describe('COUSR01C add user', () => {
  test('TS-COUSR01C-01 TS-COUSR01C-02 empty screen, then the first missing field is reported', async ({ page }) => {
    await openOption(page, /User Add/, 'CU01 · COUSR01C');
    await expect(page.getByLabel('First Name')).toBeFocused();
    await expect(page.getByTestId('message')).toHaveText('');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'First Name can NOT be empty...');
    await expect(page.getByLabel('First Name')).toHaveAttribute('aria-invalid', 'true');
    await page.getByLabel('First Name').fill('TEST');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Last Name can NOT be empty...');
    await expect(page.getByLabel('First Name')).toHaveValue('TEST');
    await expect(page.getByLabel('Last Name')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COUSR01C-08 an existing user ID is refused', async ({ page }) => {
    await openOption(page, /User Add/, 'CU01 · COUSR01C');
    await page.getByLabel('First Name').fill('TEST');
    await page.getByLabel('Last Name').fill('USER');
    await page.getByLabel('User ID').fill('ADMIN001');
    await page.getByLabel('Password').fill('PASSWORD');
    await page.getByLabel('User Type').fill('U');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'User ID already exist...');
    await expect(page.getByLabel('User ID')).toBeFocused();
  });

  test('TS-COUSR01C-07 keyboard: a new user is added and the screen is cleared', async ({ page }) => {
    await adminMenu(page);
    await page.keyboard.type('2');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU01 · COUSR01C');
    await page.keyboard.type('E2E');
    await page.keyboard.press('Tab');
    await page.keyboard.type('TESTER');
    await page.keyboard.press('Tab');
    await page.keyboard.type(NEW_USER.toLowerCase());
    await page.keyboard.press('Tab');
    await page.keyboard.type('Secret12');
    await page.keyboard.press('Tab');
    await page.keyboard.type('u');
    await page.keyboard.press('Enter');
    await expectMessage(page, `User ${NEW_USER} has been added ...`);
    await expect(page.getByLabel('User ID')).toHaveValue('');
    await expect(page.getByLabel('Password')).toHaveValue('');
    await expect(page.getByLabel('First Name')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COUSR01C-11 TS-COUSR01C-12 TS-COUSR01C-13 Q-COUSR01C-05 F4 clears, F3 and F12 return without writing', async ({ page }) => {
    await openOption(page, /User Add/, 'CU01 · COUSR01C');
    await page.getByLabel('First Name').fill('NOTSAVED');
    await page.keyboard.press('F12');
    await expectProgram(page, 'CA00 · COADM01C');
    await page.getByRole('button', { name: /User Add/ }).click();
    await expectProgram(page, 'CU01 · COUSR01C');
    await page.getByLabel('First Name').fill('NOTSAVED');
    await page.keyboard.press('F5');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByLabel('First Name')).toHaveValue('NOTSAVED');
    await page.keyboard.press('F4');
    await expect(page.getByLabel('First Name')).toHaveValue('');
    await expect(page.getByTestId('message')).toHaveText('');
    await page.getByLabel('First Name').fill('NOTSAVED');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CA00 · COADM01C');
  });
});

test.describe('COUSR02C update user', () => {
  test('TS-COUSR02C-02 TS-COUSR02C-03 TS-COUSR02C-04 empty screen, missing and unknown user IDs', async ({ page }) => {
    await openOption(page, /User Update/, 'CU02 · COUSR02C');
    await expect(page.getByLabel('Enter User ID')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'User ID can NOT be empty...');
    await page.getByLabel('Enter User ID').fill('NOSUCHUS');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'User ID NOT found...');
    await expect(page.getByLabel('First Name')).toHaveValue('');
    await expectNoSeriousA11yViolations(page);
  });

  test('TS-COUSR02C-05 TS-COUSR02C-06 TS-COUSR02C-07 keyboard: fetch, validate, and save a change', async ({ page }) => {
    await adminMenu(page);
    await page.keyboard.type('3');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU02 · COUSR02C');
    await page.keyboard.type(NEW_USER);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Press PF5 key to save your updates ...');
    await expect(page.getByLabel('First Name')).toBeFocused();
    await expect(page.getByLabel('Last Name')).toHaveValue('TESTER');
    await expect(page.getByLabel('Password')).toHaveValue('');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('F5');
    await expectMessage(page, 'Please modify to update ...');
    await page.keyboard.press('Control+A');
    await page.keyboard.press('Delete');
    await page.keyboard.press('F5');
    await expectMessage(page, 'First Name can NOT be empty...');
    await expect(page.getByLabel('First Name')).toHaveAttribute('aria-invalid', 'true');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.type('E2E');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Control+A');
    await page.keyboard.type('CHANGED');
    await page.keyboard.press('F5');
    await expectMessage(page, `User ${NEW_USER} has been updated ...`);
    await expect(page.getByLabel('Last Name')).toHaveValue('CHANGED');
  });

  test('TS-COUSR02C-14 TS-COUSR02C-15 TS-COUSR02C-11 NAV-COUSR02C-02 F7 invalid, F4 clears, F12 and F3 return', async ({ page }) => {
    await openOption(page, /User Update/, 'CU02 · COUSR02C');
    await page.getByLabel('Enter User ID').fill(NEW_USER);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Press PF5 key to save your updates ...');
    await page.keyboard.press('F7');
    await expectMessage(page, MSG.INVALID_KEY);
    await expect(page.getByLabel('Enter User ID')).toHaveValue(NEW_USER);
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter User ID')).toHaveValue('');
    await expect(page.getByLabel('Last Name')).toHaveValue('');
    await page.getByLabel('Enter User ID').fill(NEW_USER);
    await page.keyboard.press('Enter');
    await page.getByLabel('Last Name').fill('DISCARDED');
    await page.keyboard.press('F12');
    await expectProgram(page, 'CA00 · COADM01C');
    await page.getByRole('button', { name: /User Update/ }).click();
    await expectProgram(page, 'CU02 · COUSR02C');
    await page.getByLabel('Enter User ID').fill(NEW_USER);
    await page.keyboard.press('Enter');
    await expect(page.getByLabel('Last Name')).toHaveValue('CHANGED');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CA00 · COADM01C');
  });
});

test.describe('COUSR03C delete user', () => {
  test('TS-COUSR03C-03 TS-COUSR03C-05 TS-COUSR03C-11 empty, unknown, and invalid-key cases', async ({ page }) => {
    await openOption(page, /User Delete/, 'CU03 · COUSR03C');
    await expect(page.getByLabel('Enter User ID')).toBeFocused();
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'User ID can NOT be empty...');
    await page.getByLabel('Enter User ID').fill('NOSUCHUS');
    await page.keyboard.press('Enter');
    await expectMessage(page, 'User ID NOT found...');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('F8');
    await expectMessage(page, MSG.INVALID_KEY);
  });

  test('TS-COUSR03C-10 TS-COUSR03C-09 NAV-COUSR03C-02 F4 clears, F12 and F3 return without deleting', async ({ page }) => {
    await openOption(page, /User Delete/, 'CU03 · COUSR03C');
    await page.getByLabel('Enter User ID').fill(NEW_USER);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('user-details')).toContainText('CHANGED');
    await page.keyboard.press('F4');
    await expect(page.getByLabel('Enter User ID')).toHaveValue('');
    await expect(page.getByTestId('user-details')).not.toContainText('CHANGED');
    await page.keyboard.press('F12');
    await expectProgram(page, 'CA00 · COADM01C');
    await page.getByRole('button', { name: /User Delete/ }).click();
    await expectProgram(page, 'CU03 · COUSR03C');
    await page.keyboard.press('F3');
    await expectProgram(page, 'CA00 · COADM01C');
  });

  test('TS-COUSR03C-01 TS-COUSR03C-02 keyboard: fetch, then F5 deletes the user', async ({ page }) => {
    await adminMenu(page);
    await page.keyboard.type('4');
    await page.keyboard.press('Enter');
    await expectProgram(page, 'CU03 · COUSR03C');
    await page.keyboard.type(NEW_USER);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'Press PF5 key to delete this user ...');
    await expect(page.getByTestId('user-details')).toContainText('E2E');
    await expectNoSeriousA11yViolations(page);
    await page.keyboard.press('F5');
    await expectMessage(page, `User ${NEW_USER} has been deleted ...`);
    await expect(page.getByLabel('Enter User ID')).toHaveValue('');
    await page.getByLabel('Enter User ID').fill(NEW_USER);
    await page.keyboard.press('Enter');
    await expectMessage(page, 'User ID NOT found...');
  });
});
