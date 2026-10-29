import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { MSG, expectMessage, expectProgram, signOn } from '../tests/support-core';

// The stack database (make db-reset load); a USER0001 account with something to pay is looked up there
// so the smoke can run again on the same data until every linked account is paid off.
const db = process.env.CARDDEMO_DB ?? 'carddemo';

function sql(query: string): string {
  if (!process.env.CARDDEMO_DB_PASSWORD) throw new Error('CARDDEMO_DB_PASSWORD is not set (source ~/.carddemo/secrets.env)');
  const sqlcmd = process.env.SQLCMD ?? (existsSync('/opt/mssql-tools18/bin/sqlcmd') ? '/opt/mssql-tools18/bin/sqlcmd' : 'sqlcmd');
  return execFileSync(sqlcmd, ['-S', '127.0.0.1', '-d', db, '-U', 'carddemo_app', '-C', '-h', '-1', '-W', '-b', '-Q', `SET NOCOUNT ON; ${query}`], {
    env: { ...process.env, SQLCMDPASSWORD: process.env.CARDDEMO_DB_PASSWORD },
    encoding: 'utf8',
  }).trim();
}

test.describe.configure({ mode: 'serial' });

let account = '';

test.beforeAll(() => {
  account = sql(
    `SELECT TOP 1 RIGHT('00000000000' + CAST(a.account_id AS VARCHAR(11)), 11) FROM account a
     JOIN user_account ua ON ua.account_id = a.account_id
     WHERE ua.user_id = 'USER0001' AND a.current_balance > 0
       AND EXISTS (SELECT 1 FROM card_xref x WHERE x.account_id = a.account_id)
     ORDER BY a.account_id`,
  );
  expect(account, `a USER0001 account with a positive balance in ${db}`).toMatch(/^\d{11}$/);
});

test('smoke: USER0001 views a linked account and pays its balance', async ({ page }) => {
  await signOn(page, 'USER0001');
  await expectProgram(page, 'CM00 · COMEN01C');

  await page.getByLabel('Please select an option').fill('1');
  await page.keyboard.press('Enter');
  await expectProgram(page, 'CAVW · COACTVWC');
  await page.getByLabel('Account Number').fill(account);
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('info')).toHaveText('Displaying details of given Account');
  await expect(page.getByTestId('customerId')).toHaveText(/^\d{9}$/);
  await expect(page.getByTestId('ssn')).toHaveText(/^\*\*\*-\*\*-\d{4}$/);
  await page.keyboard.press('F3');
  await expectProgram(page, 'CM00 · COMEN01C');

  await page.getByLabel('Please select an option').fill('10');
  await page.keyboard.press('Enter');
  await expectProgram(page, 'CB00 · COBIL00C');
  await page.getByLabel('Enter Acct ID').fill(account);
  await page.keyboard.press('Enter');
  await expectMessage(page, MSG.BIL_CONFIRM);
  expect(Number(await page.getByTestId('balance').getAttribute('data-raw'))).toBeGreaterThan(0);
  await page.getByLabel(/Please confirm/).fill('Y');
  await page.keyboard.press('Enter');
  const message = page.getByTestId('message').locator('.msg-text');
  await expect(message).toHaveText(/^Payment successful\.  Your Transaction ID is \d{16}\.$/);
  const tranId = /(\d{16})/.exec((await message.textContent()) ?? '')![1];
  await expect(page.getByTestId('receipt')).toContainText(tranId);
  await expect(page.getByTestId('receipt')).toContainText('$0.00');
  expect(sql(`SELECT CAST(current_balance AS VARCHAR(20)) FROM account WHERE account_id = ${Number(account)}`)).toBe('0.00');
  expect(sql(`SELECT COUNT(*) FROM card_transaction WHERE tran_id = '${tranId}'`)).toBe('1');
});

test('smoke: ADMIN001 lists the users', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expectProgram(page, 'CA00 · COADM01C');
  await page.getByLabel('Please select an option').fill('1');
  await page.keyboard.press('Enter');
  await expectProgram(page, 'CU00 · COUSR00C');
  await expect(page.getByTestId('page')).toHaveText('Page 1');
  const ids = await page.getByTestId('user-row').locator('td:nth-child(2)').allTextContents();
  expect(ids).toEqual(expect.arrayContaining(['ADMIN001', 'USER0001']));
  expect(ids.length).toBeGreaterThanOrEqual(2);
});
