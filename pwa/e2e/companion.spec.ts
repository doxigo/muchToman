import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFile, writeFile } from 'node:fs/promises';

async function seed(page: Page, count = 3): Promise<void> {
  await page.goto('/');
  await expect(page.locator('h1')).toBeVisible();
  await page.evaluate(async (count) => {
    const db = await new Promise<IDBDatabase>((resolve) => { const request = indexedDB.open('muchtoman'); request.onsuccess = () => resolve(request.result); });
    const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    const session = { base: location.origin, token: 'house.secret', issuedAt: Date.now(), device: 'device', member: 'member', name: 'مریم', scope: 'family:house', key };
    const space = JSON.stringify([location.origin, 'house', session.scope, session.member]);
    const tx = db.transaction(['meta', 'records_v2'], 'readwrite');
    tx.objectStore('meta').put(session, 'session');
    for (let i = 0; i < count; i++) {
      const at = Date.now() - i * 1000;
      const value = { kind: 'transaction', ownerMemberId: i === 1 ? 'other' : 'member', sourceKind: 'manual', at, amountRial: i === 2 ? 99990 : 12340, direction: 'out', bank: 'MANUAL', categoryId: '', categoryName: '', categoryKind: 'expense', categoryEditorId: 'member', categoryUpdatedAt: 0, merchant: `رسید ${i}`, transfer: i === 2 };
      tx.objectStore('records_v2').put({ partition: space, id: `txn${i}`, scope: session.scope, updatedAt: at, device: 'device', kind: 'transaction', recordKind: 'transaction', ownerMemberId: value.ownerMemberId, deleted: false, value, at, target: '' });
    }
    await new Promise<void>((resolve) => { tx.oncomplete = () => { db.close(); resolve(); }; });
  }, count);
  await page.reload();
  await expect(page.locator('#manual')).toBeVisible();
}
async function pendingTransactions(page: Page): Promise<number> {
  return page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve) => { const r = indexedDB.open('muchtoman'); r.onsuccess = () => resolve(r.result); });
    const rows = await new Promise<Array<{ kind: string }>>((resolve) => { const r = db.transaction('outbox_v2').objectStore('outbox_v2').getAll(); r.onsuccess = () => resolve(r.result); });
    db.close(); return rows.filter((r) => r.kind === 'transaction').length;
  });
}

test('keeps draft, focus and Persian input across failed sync and prevents duplicate submits', async ({ page }) => {
  await page.route('**/v1/**', (route) => route.fulfill({ status: 503, body: '{}' }));
  await seed(page);
  await expect(page.locator('#sync')).toBeEnabled();
  await page.locator('#amount').fill('۱۲٬۳۴۵'); await page.locator('#merchant').fill('پیش‌نویس');
  await page.locator('#sync').click();
  await expect(page.locator('#sync')).toBeEnabled();
  await expect(page.locator('#amount')).toHaveValue('۱۲٬۳۴۵'); await expect(page.locator('#merchant')).toHaveValue('پیش‌نویس');
  await page.evaluate(() => {
    const encrypt = crypto.subtle.encrypt.bind(crypto.subtle);
    crypto.subtle.encrypt = async (...args: Parameters<SubtleCrypto['encrypt']>) => { await new Promise((r) => setTimeout(r, 150)); return encrypt(...args); };
    const form = document.querySelector<HTMLFormElement>('#manual')!;
    form.requestSubmit(); form.requestSubmit();
  });
  await expect(page.locator('#amount')).toHaveValue('');
  await expect.poll(() => pendingTransactions(page)).toBe(1);
  await page.reload(); await expect(page.locator('#rows')).toContainText('پیش‌نویس');
});

test('excludes transfers, exposes owner-only controls, and paginates the ledger', async ({ page }) => {
  await page.route('**/v1/**', (route) => route.fulfill({ status: 503, body: '{}' }));
  await seed(page, 123);
  await expect(page.locator('#rows > .row')).toHaveCount(50);
  const foreign = page.locator('#rows > .row').filter({ hasText: 'رسید 1' }).filter({ has: page.getByText('رسید 1', { exact: true }) });
  await expect(foreign.locator('[data-edit]')).toHaveCount(0);
  await page.locator('#next-page').click(); await expect(page.locator('#rows > .row')).toHaveCount(50);
  await page.locator('#next-page').click(); await expect(page.locator('#rows > .row')).toHaveCount(23);
  await expect(page.locator('#next-page')).toHaveCount(0);
  // 122 ordinary expenses; the 9,999-Toman transfer is excluded.
  await expect(page.locator('.hero .total')).toContainText(Math.trunc(122 * 12340 / 10).toLocaleString('fa-IR'));
});

test('installs every bundle before the first offline reload', async ({ page, context }) => {
  await seed(page);
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await expect.poll(() => page.evaluate(async () => (await navigator.serviceWorker.ready).active?.state)).toBe('activated');
  const cached = await page.evaluate(async () => {
    const names = await caches.keys(); const cache = await caches.open(names.find((n) => n.startsWith('muchtoman-shell-'))!);
    return (await cache.keys()).map((r) => new URL(r.url).pathname);
  });
  expect(cached.some((p) => p.startsWith('/assets/') && p.endsWith('.js'))).toBe(true);
  expect(cached).toContain('/modam.woff2'); expect(cached).toContain('/manifest.webmanifest');
  await context.setOffline(true); await page.reload();
  await expect(page.locator('#manual')).toBeVisible(); await expect(page.locator('#rows')).toContainText('رسید 0');
});

test('serializes sync across two browser tabs sharing one household', async ({ page, context }) => {
  let active = 0; let peak = 0; let pulls = 0;
  await context.route('**/v1/**', async (route) => {
    if (route.request().method() === 'GET') {
      active++; peak = Math.max(peak, active); pulls++;
      await new Promise((r) => setTimeout(r, 150)); active--;
      await route.fulfill({ json: { seq: 0, records: [], hasMore: false, rotationClientSecret: true } });
    } else await route.fulfill({ json: {} });
  });
  await seed(page); const second = await context.newPage(); await second.goto('/');
  await expect.poll(() => pulls).toBeGreaterThanOrEqual(2);
  await expect(page.locator('#sync')).toBeEnabled(); await expect(second.locator('#sync')).toBeEnabled();
  await Promise.all([page.locator('#sync').click(), second.locator('#sync').click()]);
  await expect.poll(() => pulls).toBeGreaterThanOrEqual(4);
  expect(peak).toBe(1);
});


test('a failed update precache keeps the previous complete shell usable offline', async ({ page, context }) => {
  await seed(page);
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await expect.poll(() => page.evaluate(async () => (await navigator.serviceWorker.ready).active?.state)).toBe('activated');
  const original = await readFile('dist/sw.js', 'utf8');
  const previousCaches = await page.evaluate(() => caches.keys());
  try {
    const failed = original.replace(/muchtoman-shell-[a-f0-9]+/, 'muchtoman-shell-failed-test')
      .replace('const PRECACHE = [', "const PRECACHE = ['http://127.0.0.1:1/unavailable.js',");
    await writeFile('dist/sw.js', failed);
    const state = await page.evaluate(async () => {
      const registration = await navigator.serviceWorker.ready;
      const outcome = new Promise<string>((resolve) => {
        registration.addEventListener('updatefound', () => {
          const worker = registration.installing!;
          worker.addEventListener('statechange', () => { if (worker.state === 'redundant' || worker.state === 'installed') resolve(worker.state); });
        }, { once: true });
      });
      await registration.update(); return outcome;
    });
    expect(state).toBe('redundant');
    const after = await page.evaluate(() => caches.keys());
    expect(after).toEqual(expect.arrayContaining(previousCaches));
    await context.setOffline(true); await page.reload();
    await expect(page.locator('#manual')).toBeVisible(); await expect(page.locator('#rows')).toContainText('رسید 0');
  } finally { await writeFile('dist/sw.js', original); }
});
