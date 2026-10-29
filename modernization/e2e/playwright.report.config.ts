import { defineConfig, devices } from '@playwright/test';

// Report check against the running stack and the real Airflow (verification/schedule/verify_report.py).
const webPort = process.env.CARDDEMO_WEB_PORT ?? '5173';

export default defineConfig({
  testDir: './verification',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  outputDir: process.env.REPORT_TEST_RESULTS ?? 'test-results/report',
  use: { baseURL: `http://127.0.0.1:${webPort}`, trace: 'retain-on-failure', acceptDownloads: true },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
