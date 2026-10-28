// Stand-in for the Airflow 3 REST API during e2e runs: issues a token, accepts
// carddemo_transaction_report runs, writes a small TRANREPT.txt into the outbox the API serves
// from, and reports each run as succeeded.
// Env: AIRFLOW_STUB_PORT (required), CARDDEMO_OUTBOX (required).
import { mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { join } from 'node:path';

const port = Number(process.env.AIRFLOW_STUB_PORT);
const outbox = process.env.CARDDEMO_OUTBOX;
if (!port || !outbox) throw new Error('AIRFLOW_STUB_PORT and CARDDEMO_OUTBOX must be set');

const TOKEN = 'e2e-stub-token';
const runs = new Map();

function reply(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch {
        resolve(null);
      }
    });
  });
}

const RUNS = /^\/api\/v2\/dags\/carddemo_transaction_report\/dagRuns(?:\/([^/]+))?$/;

createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://stub');
  if (url.pathname === '/health') return reply(res, 200, { status: 'ok' });
  if (req.method === 'POST' && url.pathname === '/auth/token') {
    const body = await readBody(req);
    if (!body?.username) return reply(res, 401, { detail: 'Invalid credentials' });
    return reply(res, 201, { access_token: TOKEN, token_type: 'bearer' });
  }
  const m = RUNS.exec(url.pathname);
  if (!m) return reply(res, 404, { detail: 'Not Found' });
  if (req.headers.authorization !== `Bearer ${TOKEN}`) return reply(res, 401, { detail: 'Not authenticated' });
  if (req.method === 'POST' && !m[1]) {
    const body = await readBody(req);
    const id = body?.dag_run_id;
    const conf = body?.conf ?? {};
    if (!id || !/^[A-Za-z0-9_.~:+-]+$/.test(id) || runs.has(id)) return reply(res, 409, { detail: 'Bad or duplicate run id' });
    const dir = join(outbox, 'reports', id);
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      join(dir, 'TRANREPT.txt'),
      `DALYREPT Daily Transaction Report  Date Range: ${conf.start_date} to ${conf.end_date}  Requested by ${conf.user_id}\n`,
    );
    runs.set(id, { dag_run_id: id, state: 'success', conf });
    return reply(res, 200, { dag_run_id: id, state: 'queued', conf });
  }
  if (req.method === 'GET' && m[1]) {
    const run = runs.get(decodeURIComponent(m[1]));
    return run ? reply(res, 200, run) : reply(res, 404, { detail: 'DAG run not found' });
  }
  return reply(res, 405, { detail: 'Method Not Allowed' });
}).listen(port, '127.0.0.1', () => console.log(`airflow stub on ${port}`));
