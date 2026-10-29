import { type Page, expect, test } from '@playwright/test';
import { expectProgram, open, signOn } from './support';

/**
 * COTTL01Y titles, transaction and program, and the MM/DD/YY HH:MM:SS clock the POPULATE-HEADER-INFO paragraphs fill.
 * The sign-on map's CICS APPLID and SYSID have no web counterpart; the approved header design omits them.
 */
async function expectHeader(page: Page, tranAndProgram: string) {
  await expectProgram(page, tranAndProgram);
  const header = page.locator('header.app-header');
  await expect(header.locator('.brand-name')).toHaveText('CardDemo');
  await expect(header.locator('.brand-sub')).toHaveText('AWS Mainframe Modernization');
  await expect(page.getByLabel('Date and time')).toHaveText(/^\d\d\/\d\d\/\d\d {2}\d\d:\d\d:\d\d$/);
}

/** The first send and a redisplay after ENTER both carry the header. */
async function expectHeaderOnEverySend(page: Page, path: string, tranAndProgram: string) {
  await open(page, path, tranAndProgram);
  await expectHeader(page, tranAndProgram);
  const turn = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes(`/${tranAndProgram.slice(-8)}`));
  await page.keyboard.press('Enter');
  expect((await turn).ok()).toBe(true);
  await expectHeader(page, tranAndProgram);
}

test('BR-COSGN00C-07 the sign-on screen header shows the titles, CC00, COSGN00C, date, and time on every send', async ({ page }) => {
  await expectHeaderOnEverySend(page, '/screens/COSGN00C', 'CC00 · COSGN00C');
});

test('BR-COMEN01C-09 the main menu header shows the titles, CM00, COMEN01C, date, and time on every send', async ({ page }) => {
  await signOn(page, 'USER0001');
  await expectHeader(page, 'CM00 · COMEN01C');
  await expectHeaderOnEverySend(page, '/screens/COMEN01C', 'CM00 · COMEN01C');
});

test('BR-COADM01C-07 the admin menu header shows the titles, CA00, COADM01C, date, and time on every send', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expectHeader(page, 'CA00 · COADM01C');
  await expectHeaderOnEverySend(page, '/screens/COADM01C', 'CA00 · COADM01C');
});

test.describe('function screens', () => {
  test.beforeEach(async ({ page }) => {
    await signOn(page, 'USER0001');
    await expectProgram(page, 'CM00 · COMEN01C');
  });

  test('BR-COTRN00C-11 the transaction list header shows CT00 and COTRN00C on every send', async ({ page }) => {
    await expectHeaderOnEverySend(page, '/screens/COTRN00C', 'CT00 · COTRN00C');
  });

  test('BR-COTRN01C-08 the transaction view header shows CT01 and COTRN01C on every send', async ({ page }) => {
    await expectHeaderOnEverySend(page, '/screens/COTRN01C', 'CT01 · COTRN01C');
  });

  test('BR-COTRN02C-11 the transaction add header shows CT02 and COTRN02C on every send', async ({ page }) => {
    await expectHeaderOnEverySend(page, '/screens/COTRN02C', 'CT02 · COTRN02C');
  });

  test('BR-CORPT00C-12 the report request header shows CR00 and CORPT00C on every send', async ({ page }) => {
    await expectHeaderOnEverySend(page, '/screens/CORPT00C', 'CR00 · CORPT00C');
  });

  test('BR-COBIL00C-10 the bill payment header shows CB00 and COBIL00C on every send', async ({ page }) => {
    await expectHeaderOnEverySend(page, '/screens/COBIL00C', 'CB00 · COBIL00C');
  });
});
