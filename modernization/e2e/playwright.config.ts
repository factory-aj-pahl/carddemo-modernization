import { defineConfig, devices } from '@playwright/test';

const db = process.env.CARDDEMO_E2E_DB ?? 'carddemo_test';

// The API and web app are started fresh for every run against the seeded e2e database (npm pretest).
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['junit', { outputFile: 'test-results/junit.xml' }]],
  use: { baseURL: 'http://127.0.0.1:5173', trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'java -jar ../backend/app/target/carddemo-api.jar',
      url: 'http://127.0.0.1:8081/actuator/health',
      env: { CARDDEMO_DB: db },
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: 'npm --prefix ../web run build && npm --prefix ../web run preview',
      url: 'http://127.0.0.1:5173',
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ],
});
