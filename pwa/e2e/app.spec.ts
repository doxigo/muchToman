import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import { randomBytes, webcrypto } from 'node:crypto';

const corpus = (file: string, id: string): string =>
  (JSON.parse(readFileSync(`../app/src/test/resources/sms/${file}`, 'utf8')) as { cases: Array<{ id: string; body: string[] }> })
    .cases.find((c) => c.id === id)!.body.join('\n');
const hex = (bytes: number): string => randomBytes(bytes).toString('hex');
/** The row's figure as TimelineRow prints it: the signed digits in an LTR isolate, the magnitude after. */
const signed = (digits: string, magnitude: string): string => `\u2066${digits}\u2069 ${magnitude}`;

// No real network: prices from a fixture, wallets and logos refused, the household API unanswered
// unless a test mocks it.
test.beforeEach(async ({ context }) => {
  await context.route('**/rates', (route) => route.fulfill({ json: { updatedAt: Date.now(), toman: { usd: 60000 }, coins: [] } }));
  await context.route(/\/(wallet-balance|coin-icon)/, (route) => route.abort());
  await context.route('**/v1/**', (route) => route.abort());
});

/** Past the first-run gate on whichever step this browser's notification permission starts it at. */
async function onboard(page: Page, path = '/'): Promise<void> {
  await page.goto(path);
  await page.getByRole('button', { name: /^(الان نه|باشه)$/ }).click();
  await onboardedOnDisk(page);
}
/** Prefs are written through to IndexedDB a beat after the screen changes; a reload before that asks again. */
async function onboardedOnDisk(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(() => new Promise((resolve) => {
    const open = indexedDB.open('muchtoman');
    open.onsuccess = () => { const get = open.result.transaction('prefs_v3').objectStore('prefs_v3').get('onboarded'); get.onsuccess = () => { open.result.close(); resolve(get.result); }; };
  }))).toBe(true);
}
async function addManual(page: Page, amount: string, merchant: string, category: string): Promise<void> {
  await page.getByRole('button', { name: 'تراکنش دستی' }).click();
  const sheet = page.getByRole('dialog', { name: 'تراکنش دستی' });
  await sheet.getByRole('textbox', { name: 'مبلغ به تومان' }).pressSequentially(amount);
  await sheet.getByRole('textbox', { name: 'بابت چی؟' }).fill(merchant);
  await sheet.getByRole('button', { name: category, exact: true }).click();
  await sheet.getByRole('button', { name: 'ثبت تراکنش' }).click();
  await expect(sheet).toHaveCount(0);
}
async function activeWorker(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(async () => (await navigator.serviceWorker.ready).active?.state)).toBe('activated');
}
async function sealed(raw: Buffer, plain: string): Promise<{ nonce: string; body: string }> {
  const key = await webcrypto.subtle.importKey('raw', new Uint8Array(raw), 'AES-GCM', false, ['encrypt']);
  const nonce = webcrypto.getRandomValues(new Uint8Array(12));
  const body = await webcrypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce }, key, new TextEncoder().encode(plain));
  return { nonce: Buffer.from(nonce).toString('base64'), body: Buffer.from(body).toString('base64') };
}

test('first run asks once, then lands on خانه with the five tabs', async ({ page }) => {
  // Headless Chromium answers «denied» before anyone asks; a phone's first run starts at «default».
  await page.addInitScript(() => {
    let answer: NotificationPermission = 'default';
    Object.defineProperty(Notification, 'permission', { get: () => answer });
    Notification.requestPermission = async () => (answer = 'granted');
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'قبل از شروع' })).toBeVisible();
  await page.getByRole('button', { name: 'اجازه بده' }).click();
  await expect(page.getByRole('heading', { name: 'یک قدم آخر' })).toBeVisible();
  await page.getByRole('button', { name: 'باشه' }).click();
  await expect(page.getByRole('tab')).toHaveText(['خانه', 'دفتر', 'آینده', 'دارایی', 'گزارش']);
  await expect(page.getByRole('tab', { name: 'خانه' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByText('جمع دارایی‌هات')).toBeVisible();
  await onboardedOnDisk(page);
  await page.reload();
  await expect(page.getByRole('tab', { name: 'خانه' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'قبل از شروع' })).toHaveCount(0);
});

test('installs every bundle before the first offline reload', async ({ page, context }) => {
  await onboard(page);
  await activeWorker(page);
  const precache = JSON.parse(/const PRECACHE = (\[.*?\]);/.exec(await readFile('dist/sw.js', 'utf8'))![1]) as string[];
  const cached = await page.evaluate(async () => {
    const names = await caches.keys(); const cache = await caches.open(names.find((n) => n.startsWith('muchtoman-shell-'))!);
    return (await cache.keys()).map((r) => new URL(r.url).pathname);
  });
  expect(precache.some((p) => p.startsWith('/assets/') && p.endsWith('.js'))).toBe(true);
  expect(precache).toContain('/modam.woff2');
  expect(cached.sort()).toEqual([...precache].sort());
  await context.setOffline(true); await page.reload();
  await expect(page.getByText('جمع دارایی‌هات')).toBeVisible();
  await page.getByRole('tab', { name: 'دفتر' }).click();
  await expect(page.getByRole('heading', { name: 'دفتر' })).toBeVisible();
});

test('a failed update precache keeps the previous complete shell usable offline', async ({ page, context }) => {
  await onboard(page);
  await addManual(page, '12000', 'بستنی', 'آبمیوه بستنی');
  await activeWorker(page);
  const original = await readFile('dist/sw.js', 'utf8');
  const previousCaches = await page.evaluate(() => caches.keys());
  try {
    await writeFile('dist/sw.js', original.replace(/muchtoman-shell-[a-f0-9]+/, 'muchtoman-shell-failed-test')
      .replace('const PRECACHE = [', "const PRECACHE = ['http://127.0.0.1:1/unavailable.js',"));
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
    expect(await page.evaluate(() => caches.keys())).toEqual(expect.arrayContaining(previousCaches));
    await context.setOffline(true); await page.reload();
    await page.getByRole('tab', { name: 'دفتر' }).click();
    await expect(page.getByRole('button').filter({ hasText: 'بستنی' })).toContainText(signed('−۱۲', 'هزار'));
  } finally { await writeFile('dist/sw.js', original); }
});

test('a manual transaction is filed, opened, deleted and brought back', async ({ page }) => {
  await onboard(page);
  await page.getByRole('button', { name: 'تراکنش دستی' }).click();
  const sheet = page.getByRole('dialog', { name: 'تراکنش دستی' });
  const amount = sheet.getByRole('textbox', { name: 'مبلغ به تومان' });
  await amount.pressSequentially('۲۵۰۰۰۰');
  await expect(amount).toHaveValue('۲۵۰٬۰۰۰');
  await sheet.getByRole('textbox', { name: 'بابت چی؟' }).fill('میوه‌فروشی');
  await sheet.getByRole('button', { name: 'خواربار', exact: true }).click();
  await sheet.getByRole('button', { name: 'ثبت تراکنش' }).click();
  await expect(sheet).toHaveCount(0);
  await page.getByRole('tab', { name: 'دفتر' }).click();
  const row = page.getByRole('button').filter({ hasText: 'میوه‌فروشی' });
  await expect(row).toContainText('خواربار');
  await expect(row).toContainText(signed('−۲۵۰', 'هزار'));
  await row.click();
  await expect(page.getByRole('heading', { level: 1, name: 'میوه‌فروشی' })).toBeVisible();
  await expect(page.getByText('دویست و پنجاه هزار تومان')).toBeVisible();
  await page.getByRole('button', { name: 'حذف این تراکنش' }).click();
  await page.getByRole('button', { name: 'مطمئنی؟ برای حذف دوباره بزن' }).click();
  await expect(page.getByRole('status')).toContainText('تراکنش پاک شد');
  await expect(row).toHaveCount(0);
  await page.getByRole('button', { name: 'برگردون' }).click();
  await expect(row).toContainText(signed('−۲۵۰', 'هزار'));
});

test('a pasted bank message lands in دفتر and sets the account balance', async ({ page }) => {
  const message = corpus('mellat.json', 'mellat-6104-withdrawal-with-balance');
  await onboard(page, `/#paste=${encodeURIComponent(message)}`);
  const sheet = page.getByRole('dialog', { name: 'پیامک بانک' });
  await expect(sheet.getByRole('textbox', { name: 'متن پیامک' })).toHaveValue(message);
  await expect(sheet).toContainText('مانده بعد از تراکنش');
  await sheet.getByRole('radio', { name: 'بانک ملت' }).click();
  await sheet.getByRole('button', { name: 'ثبت', exact: true }).click();
  await expect(sheet).toHaveCount(0);
  await expect(page.getByRole('status')).toContainText('پیامک ثبت شد');
  await page.getByRole('tab', { name: 'دفتر' }).click();
  await expect(page.getByRole('button', { name: /^بانک ملت/ })).toContainText(signed('−۴۹۶', 'هزار'));
  await page.getByRole('tab', { name: 'دارایی' }).click();
  const bank = page.getByRole('button').filter({ hasText: 'حساب‌های بانکی' });
  await expect(bank).toContainText('۱۹۹٫۰۸۸ میلیون');
  await bank.click();
  await expect(page.getByRole('dialog', { name: 'حساب‌های بانکی' })).toContainText('۱۹۹٫۰۸۸ میلیون');
});

test('a one-time code is refused on the paste sheet', async ({ page }) => {
  await onboard(page, `/#paste=${encodeURIComponent(corpus('rejected.json', 'rejected-otp-from-a-real-bank-number'))}`);
  const sheet = page.getByRole('dialog', { name: 'پیامک بانک' });
  await expect(sheet.getByRole('textbox', { name: 'متن پیامک' })).toHaveAttribute('aria-invalid', 'true');
  await expect(sheet).toContainText('این پیامک تراکنش بانکی نیست، یا رمز یک‌بارمصرفه.');
  await sheet.getByRole('radio', { name: 'بانک ملت' }).click();
  await sheet.getByRole('button', { name: 'ثبت', exact: true }).click();
  await expect(sheet).toBeVisible();
  await page.keyboard.press('Escape');
  await page.getByRole('tab', { name: 'دفتر' }).click();
  await expect(page.getByText('هنوز هیچ تراکنشی نیست')).toBeVisible();
});

test('a budget counts this month against its cap', async ({ page }) => {
  await onboard(page);
  await addManual(page, '250000', 'میوه‌فروشی', 'خواربار');
  await page.getByRole('tab', { name: 'آینده' }).click();
  await page.getByRole('button', { name: 'اولین بودجه' }).click();
  const sheet = page.getByRole('dialog', { name: 'بودجهٔ تازه' });
  await sheet.getByRole('button', { name: 'خواربار', exact: true }).click();
  await sheet.getByRole('textbox', { name: 'سقف خرج به تومان' }).fill('1000000');
  await sheet.getByRole('button', { name: 'ذخیره بودجه' }).click();
  await expect(sheet).toHaveCount(0);
  const card = page.getByRole('button', { name: /^خواربار،/ });
  await expect(card).toContainText('۲۵۰ هزار از ۱ میلیون');
  await expect(card).toContainText('۲۵٪');
});

test('joins a household and reads Android payloads that omit their defaults', async ({ page, context }) => {
  const hid = hex(16); const scope = `family:${hid}`; const raw = randomBytes(32); const maryam = hex(16); const now = Date.now();
  // What kotlinx writes with encodeDefaults=false: no `kind`, no field left at its default.
  const envelope = async (id: string, kind: string, plain: object) => ({
    id, scope, updatedAt: now, device: hex(16), kind, ownerMemberId: maryam, authorMemberId: maryam, deleted: false,
    ...(await sealed(raw, JSON.stringify(plain))),
  });
  const records = [
    await envelope(`member:${maryam}`, 'member', { memberId: maryam, name: 'مریم', sharesSms: true }),
    await envelope(`txn:${maryam}:${Buffer.from('m:1').toString('hex')}`, 'transaction',
      { ownerMemberId: maryam, at: now - 60_000, amountRial: 1234560, direction: 'out', merchant: 'نانوایی' }),
  ];
  await context.route('**/v1/**', (route) => {
    const request = route.request(); const url = new URL(request.url());
    if (url.pathname === '/v1/pair') {
      const { memberId, deviceId } = request.postDataJSON() as { memberId: string; deviceId: string };
      return route.fulfill({ json: { secret: hex(32), scopes: [scope], memberId, deviceId } });
    }
    if (url.pathname === '/v1/identity') return route.fulfill({ json: {} });
    if (url.pathname === '/v1/sync' && request.method() === 'POST') {
      return route.fulfill({ json: { seq: 1, accepted: (request.postDataJSON() as { records: unknown[] }).records.length, clamped: [] } });
    }
    if (url.pathname === '/v1/sync') return route.fulfill({ json: { seq: 1, records: url.searchParams.get('since') === '0' ? records : [], hasMore: false } });
    return route.fulfill({ status: 404, json: {} });
  });
  await onboard(page, `/#hid=${hid}&pair=${hex(8)}&scope=${scope}&k=${raw.toString('base64url')}`);
  await expect(page.getByRole('heading', { name: 'پیوستن به خانواده' })).toBeVisible();
  await page.getByRole('textbox', { name: 'اسمت' }).fill('سهیل');
  await page.getByRole('button', { name: 'پیوستن', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'اعضای خانواده (۲)' })).toBeVisible();
  await expect(page.getByRole('button').filter({ hasText: 'مریم' })).toContainText('به اشتراک می‌ذاره');
  await page.getByRole('button', { name: 'برگشت' }).click();
  await page.getByRole('tab', { name: 'دفتر' }).click();
  const row = page.getByRole('button').filter({ hasText: 'نانوایی' });
  await expect(row).toContainText('از مریم');
  await expect(row).toContainText(signed('−۱۲۳٫۴', 'هزار'));
});
