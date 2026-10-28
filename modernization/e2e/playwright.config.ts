import { defineConfig, devices } from '@playwright/test';

const db = process.env.CARDDEMO_E2E_DB ?? 'carddemo_test';
// Parallel worktrees run their own API and web servers on separate ports.
const apiPort = process.env.CARDDEMO_API_PORT ?? '8081';
const webPort = process.env.CARDDEMO_WEB_PORT ?? '5173';
const api = `http://127.0.0.1:${apiPort}`;
const web = `http://127.0.0.1:${webPort}`;

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
      command: 'java -jar ../backend/app/target/carddemo-api.jar',
      url: `${api}/actuator/health`,
      env: { CARDDEMO_DB: db, SERVER_PORT: apiPort },
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
