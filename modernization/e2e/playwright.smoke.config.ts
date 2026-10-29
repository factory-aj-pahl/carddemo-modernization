import { defineConfig, devices } from '@playwright/test';

// Smoke flow against the running stack (make up): nothing is started or seeded here.
const webPort = process.env.CARDDEMO_WEB_PORT ?? '5173';

export default defineConfig({
  testDir: './smoke',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['junit', { outputFile: 'test-results/smoke-junit.xml' }]],
  outputDir: 'test-results/smoke',
  use: { baseURL: `http://127.0.0.1:${webPort}`, trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
