import { test } from '@playwright/test';
import { expectMessage, expectProgram, fixtures, MSG, open, signOn } from './support';

// Screenshots for the UI review; runs only when CAPTURE_DIR is set.
const dir = process.env.CAPTURE_DIR;
test.skip(!dir, 'set CAPTURE_DIR to capture screenshots');

const shot = (page: import('@playwright/test').Page, name: string) => page.screenshot({ path: `${dir}/${name}.png`, fullPage: true });

test('capture slice screens', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 820 });
  await open(page, '/screens/COSGN00C', 'CC00 · COSGN00C');
  await shot(page, 'COSGN00C-initial');
  await page.getByLabel('User ID').fill('user0001');
  await page.keyboard.press('Enter');
  await expectMessage(page, MSG.NO_PASSWORD);
  await shot(page, 'COSGN00C-error');
  await signOn(page, 'ADMIN001');
  await expectProgram(page, 'CA00 · COADM01C');
  await shot(page, 'COADM01C-initial');
  await signOn(page, 'USER0001');
  await expectProgram(page, 'CM00 · COMEN01C');
  await shot(page, 'COMEN01C-initial');
  await page.getByLabel('Please select an option').fill('99');
  await page.keyboard.press('Enter');
  await expectMessage(page, MSG.BAD_OPTION);
  await shot(page, 'COMEN01C-error');
  await open(page, '/screens/COBIL00C', 'CB00 · COBIL00C');
  await shot(page, 'COBIL00C-initial');
  await page.getByLabel('Enter Acct ID').fill(fixtures.spareLinkedAccount);
  await page.keyboard.press('Enter');
  await expectMessage(page, MSG.BIL_CONFIRM);
  await shot(page, 'COBIL00C-confirm');
  await page.getByLabel(/Please confirm/).fill('Y');
  await page.keyboard.press('Enter');
  await page.getByTestId('receipt').waitFor();
  await shot(page, 'COBIL00C-receipt');
});
