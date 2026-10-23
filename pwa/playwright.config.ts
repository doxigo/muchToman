import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:4174', launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } },
  webServer: { command: 'pnpm run build && pnpm exec vite preview --host 127.0.0.1 --port 4174', url: 'http://127.0.0.1:4174', reuseExistingServer: false },
});
