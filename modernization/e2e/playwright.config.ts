import { defineConfig, devices } from '@playwright/test';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const db = process.env.CARDDEMO_E2E_DB ?? 'carddemo_test';
// Parallel worktrees run their own API and web servers on separate ports.
const apiPort = process.env.CARDDEMO_API_PORT ?? '8081';
const webPort = process.env.CARDDEMO_WEB_PORT ?? '5173';
const api = `http://127.0.0.1:${apiPort}`;
const web = `http://127.0.0.1:${webPort}`;
// CORPT00C launches report runs on a stand-in Airflow that writes the report into this outbox.
const airflowPort = process.env.CARDDEMO_AIRFLOW_STUB_PORT ?? String(Number(apiPort) + 1000);
const outbox = join(tmpdir(), `carddemo-e2e-outbox-${apiPort}`);

// The API and web app are started fresh for every run against the seeded e2e database (npm pretest).
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['junit', { outputFile: 'test-results/junit.xml' }]],
  use: { baseURL: web, trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'node scripts/airflow-stub.mjs',
      url: `http://127.0.0.1:${airflowPort}/health`,
      env: { AIRFLOW_STUB_PORT: airflowPort, CARDDEMO_OUTBOX: outbox },
      reuseExistingServer: false,
      timeout: 30_000,
    },
    {
      command: 'java -jar ../backend/app/target/carddemo-api.jar',
      url: `${api}/actuator/health`,
      env: {
        CARDDEMO_DB: db,
        SERVER_PORT: apiPort,
        AIRFLOW_BASE_URL: `http://127.0.0.1:${airflowPort}`,
        AIRFLOW_PASSWORD: 'e2e-stub',
        CARDDEMO_OUTBOX: outbox,
      },
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: 'npm --prefix ../web run build && npm --prefix ../web run preview',
      url: web,
      env: { CARDDEMO_API_URL: api, CARDDEMO_WEB_PORT: webPort },
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ],
});
