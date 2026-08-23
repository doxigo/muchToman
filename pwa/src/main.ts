import { importKey, readPairing } from './crypto';
import type { Pairing } from './crypto';
import { allRecords, getMeta, setMeta } from './db';
import { inJalaliMonth, memberId, save, saveProfile, syncNow } from './sync';
import type { CategoryDecision, Entry, MemberProfile, Session } from './sync';
import { parsePasted } from './paste';

const app = document.getElementById('app')!;
const FA_DIGITS = '۰۱۲۳۴۵۶۷۸۹';
const fa = (n: number): string =>
  Math.round(n).toLocaleString('en-US').replace(/[0-9]/g, (d) => FA_DIGITS[Number(d)]);
const toman = (rial: number): string => fa(rial / 10);

/**
 * How wide a figure is, in ems, so the field can size it to fit rather than wrap — the app's
 * autosize, done where the string is. Tabular digits all have one advance, so length is width:
 * ≈0.66em a glyph at `wdth` 90, plus 1.3 for the "تومان" the hero total carries.
 */
const fit = (figure: string, unit = 0): string => (figure.length * 0.66 + unit).toFixed(2);

function el(html: string): HTMLElement {
  const wrapper = document.createElement('div');
  wrapper.innerHTML = html.trim();
  return wrapper.firstElementChild as HTMLElement;
}

function escape(value: string): string {
  return value.replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
  );
}

interface StoredSession {
  base: string;
  token: string;
  issuedAt?: number;
  device: string;
  member?: string;
  name?: string;
  scope: string;
  /** The non-extractable CryptoKey itself; `number[]` is the pre-release shape, migrated once. */
  key: CryptoKey | number[];
}

async function loadSession(): Promise<Session | null> {
  const stored = await getMeta<StoredSession>('session');
  if (!stored) return null;
  // The raw key bytes used to sit in this record beside the token, which defeated the point of
  // a non-extractable CryptoKey: anything that could read IndexedDB could lift the key itself.
  // Structured clone stores the CryptoKey object directly, so the bytes exist nowhere at rest —
  // an old-shape record is imported once, rewritten, and the bytes are gone.
  const legacyKey = Array.isArray(stored.key);
  const session: Session = {
    base: stored.base,
    token: stored.token,
    issuedAt: stored.issuedAt ?? Date.now(),
    device: stored.device,
    member: stored.member ?? memberId(),
    name: stored.name?.trim() || 'عضو خانواده',
    scope: stored.scope,
    key: legacyKey ? await importKey(new Uint8Array(stored.key as number[])) : (stored.key as CryptoKey),
  };
  if (legacyKey || !stored.member || !stored.name || !stored.issuedAt) await setMeta('session', session);
  return session;
}

async function redeemPairing(pairing: Pairing, name: string): Promise<Session> {
  const member = memberId();
  const device = memberId();
  const res = await fetch(`${pairing.url}/v1/pair`, {
    method: 'POST',
    headers: { authorization: `Bearer ${pairing.hid}.${'0'.repeat(64)}` },
    body: JSON.stringify({ code: pairing.code, memberId: member, deviceId: device }),
  });
  if (!res.ok) throw new Error('این کد دیگه معتبر نیست.');
  const { secret } = (await res.json()) as { secret: string };
  const session: Session = {
    base: pairing.url,
    token: `${pairing.hid}.${secret}`,
    issuedAt: Date.now(),
    device,
    member,
    name: name.trim().slice(0, 32),
    scope: pairing.scope,
    // Imported before it is stored, so what lands in IndexedDB is only ever the
    // non-extractable object — the raw bytes stay in the URL fragment they arrived in.
    key: await importKey(pairing.key),
  };
  await setMeta('session', session);
  await saveProfile(session);
  return session;
}

/**
 * Rounded, stroked, 24px: the app's own icon language, drawn rather than pulled from a set —
 * one glyph is not worth a font, and Modam has no `↻`.
 */
const REFRESH = `
  <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor"
       stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M20 12a8 8 0 1 1-2.34-5.66" /><path d="M20 4v4.6h-4.6" />
  </svg>`;

function renderPairing(error?: string): void {
  app.replaceChildren(
    el(`
      <div>
        <header class="hero"><h1>چقدر تومن</h1></header>
        <div class="page stack">
          <div class="panel"><p>از بخش خانواده روی گوشی یکی از اعضا، کد دعوت رو اسکن کن.</p></div>
          ${error ? `<p class="error">${escape(error)}</p>` : ''}
        </div>
      </div>
    `),
  );
}

function renderJoin(pairing: Pairing, originalHash: string): void {
  const nativeUrl = `muchtoman://join${originalHash}`;
  app.replaceChildren(
    el(`
      <div>
        <header class="hero">
          <h1>پیوستن به خانواده</h1>
          <p class="hero-label">اگر اپ اندروید نصب شده، دعوت رو مستقیم داخل اپ باز کن.</p>
        </header>
        <div class="page stack">
          <button id="open-native" class="primary">باز کردن در اپ اندروید</button>
          <div class="muted">یا در همین مرورگر ادامه بده:</div>
          <form id="join" class="panel">
            <label for="name">اسم تو</label>
            <input id="name" maxlength="32" required />
            <button type="submit">پیوستن در مرورگر</button>
          </form>
          <p id="join-error" class="error"></p>
        </div>
      </div>
    `),
  );
  app.querySelector<HTMLButtonElement>('#open-native')!.addEventListener('click', () => {
    location.href = nativeUrl;
  });
  app.querySelector<HTMLFormElement>('#join')!.addEventListener('submit', async (event) => {
    event.preventDefault();
    const name = app.querySelector<HTMLInputElement>('#name')!.value.trim();
    if (!name) return;
    try {
      const session = await redeemPairing(pairing, name);
      await renderLedger(session, 'به خانواده پیوستی.');
      void trySync(session);
    } catch (error) {
      app.querySelector('#join-error')!.textContent = error instanceof Error ? error.message : String(error);
    }
  });
}

function isEntry(value: unknown): value is Entry {
  if (value == null || typeof value !== 'object') return false;
  const row = value as Record<string, unknown>;
  return typeof row.at === 'number' && typeof row.amountRial === 'number';
}

async function renderLedger(session: Session, note?: string, warn = false): Promise<void> {
  const records = await allRecords();
  const profiles = new Map<string, MemberProfile>();
  const decisions = new Map<string, CategoryDecision>();
  for (const record of records) {
    if (record.value == null || typeof record.value !== 'object') continue;
    const value = record.value as Record<string, unknown>;
    const recordKind = record.kind ?? 'legacy';
    if (
      recordKind === 'member' && value.kind === 'member' &&
      typeof value.memberId === 'string' && value.memberId === record.ownerMemberId
    ) {
      profiles.set(value.memberId, record.value as MemberProfile);
    }
    if (recordKind === 'category' && value.kind === 'category' && typeof value.target === 'string') {
      decisions.set(value.target, record.value as CategoryDecision);
    }
  }
  const rows = records
    .filter((record) => {
      const kind = record.kind ?? 'legacy';
      return (kind === 'transaction' || kind === 'legacy') && isEntry(record.value);
    })
    .map((record) => {
      const entry = record.value as Entry;
      const ownerId = (record.kind ?? 'legacy') === 'legacy'
        ? record.authorMemberId || ''
        : record.ownerMemberId || record.authorMemberId || '';
      const category = decisions.get(record.id);
      return {
        id: record.id,
        entry,
        ownerName: profiles.get(ownerId)?.name || (ownerId === session.member ? session.name : 'عضو خانواده'),
        categoryName: category?.categoryName || entry.categoryName || 'دسته‌بندی نشده',
      };
    })
    .sort((a, b) => b.entry.at - a.entry.at);

  // The hero says «این ماه», so it sums this Jalali month and nothing else — the list below
  // still carries everything.
  const now = Date.now();
  const monthRows = rows.filter((r) => inJalaliMonth(r.entry.at, now));
  const income = monthRows.filter((r) => r.entry.direction === 'in').reduce((s, r) => s + r.entry.amountRial, 0);
  const spent = monthRows.filter((r) => r.entry.direction === 'out').reduce((s, r) => s + r.entry.amountRial, 0);
  const members = [...profiles.values()].sort((a, b) => a.name.localeCompare(b.name, 'fa'));
  const [net, earned, paid] = [toman(income - spent), toman(income), toman(spent)];

  app.replaceChildren(
    el(`
      <div>
        <header class="hero">
          <p class="hero-label">این ماه</p>
          <div class="figure total" style="--fit:${fit(net, 1.3)}">${net}<span class="unit">تومان</span></div>
          <div class="hero-actions">
            <button id="sync" class="hero-action" type="button">
              <span class="disc">${REFRESH}</span>
              همگام کن
            </button>
          </div>
          ${note ? `<p class="strip${warn ? ' warn' : ''}">${escape(note)}</p>` : ''}
        </header>
        <div class="page stack">
          <div class="panel">
            <div class="flow">
              <div style="--fit:${fit(earned)}">
                <div class="muted">درآمد این ماه</div>
                <span class="figure amount in">${earned}</span>
              </div>
              <div style="--fit:${fit(paid)}">
                <div class="muted">خرج این ماه</div>
                <span class="figure amount">${paid}</span>
              </div>
            </div>
            ${income > 0 || spent > 0 ? `
              <div class="bar">
                ${income > 0 ? `<span class="in" style="flex:${income}"></span>` : ''}
                ${spent > 0 ? `<span class="out" style="flex:${spent}"></span>` : ''}
              </div>` : ''}
          </div>
          ${members.length ? `<div class="muted">اعضا: ${members.map((m) => escape(m.name)).join('، ')}</div>` : ''}
          <div class="panel">
            <label for="paste">متن پیامک بانکی رو اینجا بچسبون</label>
            <textarea id="paste" rows="3"></textarea>
            <button id="read" type="button">خواندن پیامک</button>
            <form id="manual">
              <label for="amount">مبلغ (تومان)</label>
              <input id="amount" inputmode="numeric" required />
              <label for="direction">نوع تراکنش</label>
              <select id="direction"><option value="out">خرج</option><option value="in">درآمد</option></select>
              <label for="merchant">بابت چه چیزی؟</label>
              <input id="merchant" />
              <button class="primary" type="submit">ثبت</button>
            </form>
          </div>
          <div class="section">
            <h2>تراکنش‌ها</h2>
            ${rows.length ? `<span class="muted">${fa(rows.length)} مورد</span>` : ''}
          </div>
          ${rows.length ? `
            <div class="band" id="rows">
              ${rows.map((r) => `
                <div class="row">
                  <div class="grow">
                    <div>${escape(r.entry.merchant || 'بدون نام')}</div>
                    <div class="muted">${escape(r.ownerName)} • ${escape(r.categoryName)}</div>
                  </div>
                  <div class="figure amount ${r.entry.direction === 'in' ? 'in' : ''}">
                    ${r.entry.direction === 'in' ? '+' : '−'}${toman(r.entry.amountRial)}
                  </div>
                </div>`).join('')}
            </div>` : `
            <div class="panel" id="rows">
              <p>هنوز تراکنشی ثبت نشده</p>
              <p class="muted">اولین چیزی که ثبت کنی، همین‌جا می‌مونه.</p>
            </div>`}
        </div>
      </div>
    `),
  );

  const amount = app.querySelector<HTMLInputElement>('#amount')!;
  const direction = app.querySelector<HTMLSelectElement>('#direction')!;
  const merchant = app.querySelector<HTMLInputElement>('#merchant')!;
  app.querySelector('#read')!.addEventListener('click', () => {
    const text = app.querySelector<HTMLTextAreaElement>('#paste')!.value;
    const read = parsePasted(text);
    if (read.amountRial != null) amount.value = String(Math.round(read.amountRial / 10));
    if (read.direction) direction.value = read.direction;
    amount.focus();
  });
  app.querySelector('#manual')!.addEventListener('submit', async (event) => {
    event.preventDefault();
    const value = Number(amount.value.replace(/[^0-9]/g, ''));
    if (!Number.isFinite(value) || value <= 0) return;
    await save(session, {
      at: Date.now(),
      amountRial: value * 10,
      direction: direction.value as 'in' | 'out',
      bank: 'MANUAL',
      categoryId: '',
      categoryName: '',
      categoryKind: 'expense',
      categoryUpdatedAt: 0,
      merchant: merchant.value.trim(),
      note: '',
    });
    await renderLedger(session, 'ثبت شد.');
    void trySync(session);
  });
  app.querySelector('#sync')!.addEventListener('click', () => void trySync(session, true));
}

async function trySync(session: Session, loud = false): Promise<void> {
  try {
    const { sent, received } = await syncNow(session);
    if (loud || received > 0) {
      await renderLedger(session, loud ? `${fa(sent)} مورد فرستادیم، ${fa(received)} مورد گرفتیم.` : undefined);
    }
  } catch {
    if (loud) await renderLedger(session, 'اتصال نشد. تغییرات ذخیره شدن و بعداً فرستاده می‌شن.', true);
  }
}

async function start(): Promise<void> {
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  try {
    const pairing = readPairing(location.hash);
    if (pairing) {
      const originalHash = location.hash;
      history.replaceState(null, '', location.pathname);
      renderJoin(pairing, originalHash);
      return;
    }
    const session = await loadSession();
    if (!session) {
      renderPairing();
      return;
    }
    await saveProfile(session);
    await renderLedger(session);
    void trySync(session);
  } catch (error) {
    renderPairing(error instanceof Error ? error.message : String(error));
  }
}

void start();
