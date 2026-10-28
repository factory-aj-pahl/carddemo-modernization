// Resets the e2e database, loads the sample data and users, and records fixture account IDs.
// Database: CARDDEMO_E2E_DB (default carddemo_test). Needs CARDDEMO_DB_PASSWORD in the environment.
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../../..');
const db = process.env.CARDDEMO_E2E_DB ?? 'carddemo_test';
if (!process.env.CARDDEMO_DB_PASSWORD) throw new Error('CARDDEMO_DB_PASSWORD is not set (source ~/.carddemo/secrets.env)');

const jar = resolve(repo, 'modernization/backend/batch/target/carddemo-batch.jar');
const env = { ...process.env, CARDDEMO_DB: db };
const batch = (...args) =>
  execFileSync('java', ['-jar', jar, '--spring.flyway.clean-disabled=false', ...args], { env, stdio: ['ignore', 'pipe', 'inherit'] });

batch('reset');
batch(
  'load',
  resolve(repo, 'app/data/ASCII'),
  `usrsec=${resolve(repo, 'app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS')}`,
  `ims=${resolve(repo, 'app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat')}`,
);
// The transaction screens (COTRN00C-02C) browse TRANSACT; their spec's test data is the 300 daily
// records loaded as the transaction file.
const tranDir = mkdtempSync(join(tmpdir(), 'carddemo-e2e-transact-'));
copyFileSync(resolve(repo, 'app/data/ASCII/dailytran.txt'), join(tranDir, 'transact.txt'));
batch('load', tranDir);
rmSync(tranDir, { recursive: true, force: true });

const sqlcmd = process.env.SQLCMD ?? (existsSync('/opt/mssql-tools18/bin/sqlcmd') ? '/opt/mssql-tools18/bin/sqlcmd' : 'sqlcmd');
const sql = (query) =>
  execFileSync(sqlcmd, ['-S', '127.0.0.1', '-d', db, '-U', 'carddemo_app', '-C', '-h', '-1', '-W', '-Q', `SET NOCOUNT ON; ${query}`], {
    env: { ...process.env, SQLCMDPASSWORD: process.env.CARDDEMO_DB_PASSWORD },
    encoding: 'utf8',
  }).trim();

const id11 = "RIGHT('00000000000' + CAST(a.account_id AS VARCHAR(11)), 11)";
const hasXref = 'EXISTS (SELECT 1 FROM card_xref x WHERE x.account_id = a.account_id)';
const [linked, spare] = sql(
  `SELECT TOP 2 ${id11} FROM account a JOIN user_account ua ON ua.account_id = a.account_id
   WHERE ua.user_id = 'USER0001' AND a.current_balance > 0 AND ${hasXref} ORDER BY a.account_id`,
).split(/\s+/);
const unlinked = sql(
  `SELECT TOP 1 ${id11} FROM account a WHERE ${hasXref} AND a.account_id NOT IN
   (SELECT account_id FROM user_account WHERE user_id = 'USER0001') ORDER BY a.account_id`,
);
if (![linked, spare, unlinked].every((v) => /^\d{11}$/.test(v ?? ''))) throw new Error('fixture query found too few accounts');
const userCards = `SELECT x.card_num FROM card_xref x JOIN user_account ua ON ua.account_id = x.account_id WHERE ua.user_id = 'USER0001'`;
const firstTranId = sql('SELECT MIN(tran_id) FROM card_transaction');
const userTranId = sql(`SELECT MIN(tran_id) FROM card_transaction WHERE card_num IN (${userCards})`);
const otherTranId = sql(`SELECT MIN(tran_id) FROM card_transaction WHERE card_num NOT IN (${userCards})`);
if (![firstTranId, userTranId, otherTranId].every((v) => /^\d{16}$/.test(v ?? ''))) throw new Error('fixture query found too few transactions');
// Each spec that pays gets its own account so specs stay independent of run order.
writeFileSync(
  resolve(here, '../.fixtures.json'),
  JSON.stringify(
    { database: db, linkedAccount: linked, spareLinkedAccount: spare, unlinkedAccount: unlinked, firstTranId, userTranId, otherTranId },
    null,
    2,
  ),
);
console.log(`seeded ${db}`);
