// Store-listing images (CafeBazaar) from docs/screenshots, so they follow the README captures
// whenever those are retaken. Needs pwa/'s dev dependencies (`cd pwa && npm ci`, then
// `npx playwright install chromium`). Writes docs/store/<id>.png, or .jpg for promo-*, which
// Bazaar takes only as JPEG. Usage: node tools/store/render.mjs [id ...]
//
// Bazaar's slots (developers.cafebazaar.ir, 2026-09): icon PNG 512², square, no corner radius or
// shadow (Bazaar adds both); header ("تصویر سرصفحه") PNG 5:2, ≥720×288; screenshots any size, up to
// 12; promo screenshots JPEG 16:9, ≥1152×648. Every file ≤1 MB.
import { chromium } from '../../pwa/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const out = new URL('../../docs/store/', import.meta.url);
mkdirSync(out, { recursive: true });
// file:// pages may not load a file:// font without this
const browser = await chromium.launch({ args: ['--allow-file-access-from-files'] });
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
await page.goto(new URL('slides.html', import.meta.url).href);
await page.evaluate(() => document.fonts.ready);
const want = process.argv.slice(2);
for (const id of (await page.evaluate(() => window.ids())).filter(id => !want.length || want.includes(id))) {
  const { w, h } = await page.evaluate(id => window.show(id), id);
  await page.setViewportSize({ width: w, height: h });
  await page.evaluate(() => Promise.all([...document.images].map(i => i.decode().catch(() => {}))));
  await page.waitForTimeout(250); // callouts paint the same captures as CSS backgrounds
  const jpeg = id.startsWith('promo');
  const path = fileURLToPath(new URL(`${id}.${jpeg ? 'jpg' : 'png'}`, out));
  await page.screenshot(jpeg ? { path, type: 'jpeg', quality: 90 } : { path });
  console.log(path, `${w}x${h}`);
}
await browser.close();
