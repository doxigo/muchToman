// The PWA captures the ad's iPhone scene shows, from the real app: starts pwa/'s dev server with
// the ?demo=1 household (the same seeded ledger the phone's captures come from), light theme, at
// iPhone 15/16 size (393pt @3x). Safari leaves the page 683pt between the status bar and its
// toolbar; installed on the home screen it gets 793pt. Writes docs/screenshots/pwa-*.png.
// Usage: node tools/ad/capture-pwa.mjs   (live rates, so retake it with the phone's captures)
import { chromium } from '../../pwa/node_modules/playwright/index.mjs';
import { createServer } from '../../pwa/node_modules/vite/dist/node/index.js';
import { fileURLToPath } from 'node:url';

const pwa = fileURLToPath(new URL('../../pwa/', import.meta.url));
const shots = [['pwa-ledger-safari', 'LEDGER', 683], ['pwa-report', 'REPORT', 793]];

const server = await createServer({ root: pwa, configFile: pwa + 'vite.config.ts', logLevel: 'error',
  server: { host: '127.0.0.1', port: 5199, strictPort: true } });
await server.listen();
const browser = await chromium.launch({ args: ['--force-color-profile=srgb'] });
for (const [name, tab, height] of shots) {
  const context = await browser.newContext({
    viewport: { width: 393, height }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, colorScheme: 'light',
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1',
    locale: 'fa-IR', timezoneId: 'Asia/Tehran',
  });
  const page = await context.newPage();
  await page.goto(`http://127.0.0.1:5199/?demo=1&tab=${tab}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2500); // the rates land after the first paint
  await page.screenshot({ path: fileURLToPath(new URL(`../../docs/screenshots/${name}.png`, import.meta.url)) });
  await context.close();
  console.log(`docs/screenshots/${name}.png`);
}
await browser.close();
await server.close();
