import { importKey, readPairing } from './crypto';
import type { Pairing } from './crypto';
import { activateSession, decisionsFor, getMeta, partition, pendingCount, recordPage, recordsBetween, recordsOfKind, savedSessions, setMeta } from './db';
import type { PageCursor, StoredRecord } from './db';
import { inJalaliMonth, memberId, remove, save, saveProfile, syncNow, uuid7 } from './sync';
import type { CategoryDecision, Entry, MemberProfile, NoteDecision, Session } from './sync';
import { parsePasted } from './paste';

const app = document.getElementById('app')!;
import { fa, toman, parseToman } from './money';
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
  await activateSession(session);
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
  await activateSession(session);
  await saveProfile(session);
  return session;
}

/**
 * The app's one pen — round caps, round joins, 2dp at 24 — so a mark drawn here is the same
 * object as the one `TabBar.kt` draws with `pen()`. Drawn rather than pulled from a set: one
 * glyph is not worth a font, Modam has no `↻`, and an emoji is not an icon.
 */
const pen = (body: string): string =>
  `<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor"
        stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;

const REFRESH = pen('<path d="M20 12a8 8 0 1 1-2.34-5.66" /><path d="M20 4v4.6h-4.6" />');

/** `CompanionGlyph` — the app's one drawing of a person: a head, and the shoulders under it. */
const PERSON = pen('<circle cx="12" cy="8.2" r="3.4" /><path d="M5 19.6a7 7 0 0 1 14 0" />');

/** What a transaction is, before there are any: the receipt the ledger's empty state teaches. */
const RECEIPT = pen(
  '<path d="M6 4h12v16l-3-1.6L12 20l-3-1.6L6 20z" /><path d="M9.2 8.6h5.6" /><path d="M9.2 12.4h5.6" />',
);

/** The invitation itself: the scan frame the code is held inside on the other phone. */
const SCAN = pen(
  '<path d="M4 8.5V6a2 2 0 0 1 2-2h2.5" /><path d="M15.5 4H18a2 2 0 0 1 2 2v2.5" />' +
    '<path d="M20 15.5V18a2 2 0 0 1-2 2h-2.5" /><path d="M8.5 20H6a2 2 0 0 1-2-2v-2.5" />' +
    '<path d="M7.5 12h9" />',
);

/**
 * The cold landing: someone opened this address with no invitation in the URL and nothing
 * stored. There is no answer to put in a hero card, so there is no hero — an empty green slab
 * standing in for a total she does not have yet is the retired world's habit, not this one's.
 * The drawn glyph on its `primaryContainer` disc teaches what to go and do instead.
 */
function renderPairing(error?: string): void {
  app.replaceChildren(
    el(`
      <div class="page">
        <h1 class="title">چقدر تومن</h1>
        <div class="empty">
          <span class="disc">${SCAN}</span>
          <p>هنوز به خانواده‌ای وصل نیستی.</p>
          <p class="muted">
            از بخش «خانواده» روی گوشی یکی از اعضا کد دعوت رو بگیر. با همون لینک، این صفحه
            خودش باز می‌شه.
          </p>
        </div>
        ${error ? `<p class="error" role="alert">${escape(error)}</p>` : ''}
      </div>
    `),
  );
}

/**
 * An invitation, opened. Two ways in, so the page is built as a fork rather than as a stack:
 * the loud pill hands the whole thing to the app that already holds her ledger, and under a
 * rule — not a stray line of grey text — the browser keeps her here. The card below is the
 * app's own `JoinCard`, to the word: what the name is for, then the field, then the answer.
 */
function renderJoin(pairing: Pairing, originalHash: string, previous?: Session | null): void {
  const nativeUrl = `muchtoman://join${originalHash}`;
  app.replaceChildren(
    el(`
      <div class="page">
        <h1 class="title">پیوستن به خانواده</h1>
        <p class="muted" style="margin:var(--s) 0 var(--xl)">
          اگر اپ اندروید روی همین گوشیه، دعوت رو همون‌جا باز کن. دفتر و پیامک‌های بانکی
          فقط اونجا خونده می‌شن.
        </p>
        <button id="open-native" class="primary" type="button">باز کردن در اپ اندروید</button>
        <p class="or">یا</p>
        ${previous ? '<p class="muted">با پیوستن، دفتر قبلی و تغییرات نفرستاده‌اش روی این مرورگر می‌مونن. از «خانواده‌ها» می‌تونی برگردی.</p><button id="cancel-join" type="button">برگشت به خانواده قبلی</button>' : ''}
        <form id="join" class="panel" novalidate>
          <p class="panel-title">ادامه در همین مرورگر</p>
          <p class="muted">این اسم کنار تراکنش‌های تو دیده می‌شه.</p>
          <label for="name">اسم تو</label>
          <input id="name" name="name" maxlength="32" autocomplete="nickname"
                 enterkeyhint="go" placeholder="مثلاً مریم" required />
          <button id="join-submit" type="submit" disabled>پیوستن</button>
          <p id="join-error" class="error" role="alert"></p>
        </form>
      </div>
    `),
  );
  app.querySelector('#cancel-join')?.addEventListener('click', () => { if (previous) void renderLedger(previous); });
  let joining = false;
  const name = app.querySelector<HTMLInputElement>('#name')!;
  const submit = app.querySelector<HTMLButtonElement>('#join-submit')!;
  const failure = app.querySelector<HTMLParagraphElement>('#join-error')!;
  // `enabled = name.isNotBlank() && !working`, exactly as the app arms the same button: an
  // answer she cannot give yet should not look pressable.
  name.addEventListener('input', () => {
    submit.disabled = joining || !name.value.trim();
    failure.textContent = '';
  });
  app.querySelector<HTMLButtonElement>('#open-native')!.addEventListener('click', () => {
    location.href = nativeUrl;
  });
  app.querySelector<HTMLFormElement>('#join')!.addEventListener('submit', async (event) => {
    event.preventDefault();
    const her = name.value.trim();
    if (!her || joining) return;
    joining = true; name.disabled = true;
    submit.disabled = true;
    submit.textContent = 'در حال پیوستن...';
    failure.textContent = '';
    try {
      const session = await redeemPairing(pairing, her);
      await renderLedger(session, 'به خانواده پیوستی.');
      void trySync(session);
    } catch (error) {
      failure.textContent = error instanceof Error ? error.message : String(error);
      submit.disabled = false;
      submit.textContent = 'پیوستن';
      name.focus();
    } finally { joining = false; name.disabled = false; submit.disabled = !name.value.trim(); }
  });
}

function isEntry(value: unknown): value is Entry {
  if (value == null || typeof value !== 'object') return false;
  const row = value as Record<string, unknown>;
  return typeof row.at === 'number' && Number.isFinite(row.at) && Number.isSafeInteger(row.amountRial) && Number(row.amountRial) > 0 && (row.direction === 'in' || row.direction === 'out');
}

interface Draft { localId?: string; amount: string; direction: string; merchant: string; paste: string; editing?: string; at?: number; pastedRial?: number; pastedAmount?: string }
const drafts = new Map<string, Draft>();
const submitting = new Set<string>();
const syncing = new Set<string>();
const pages = new Map<string, PageCursor[]>();
let activeSpace = '';
let renderGeneration = 0;
const emptyDraft = (): Draft => ({ amount: '', direction: 'out', merchant: '', paste: '' });

async function renderLedger(session: Session, note?: string, warn = false): Promise<void> {
  const space = partition(session); activeSpace = space;
  const generation = ++renderGeneration;
  app.querySelectorAll<HTMLButtonElement>('#next-page, #previous-page').forEach((button) => { button.disabled = true; });
  const focused = document.activeElement instanceof HTMLInputElement || document.activeElement instanceof HTMLTextAreaElement
    ? { id: document.activeElement.id, start: document.activeElement.selectionStart, end: document.activeElement.selectionEnd } : null;
  const stack = pages.get(space) ?? [];
  const now = Date.now();
  const [page, monthRecords, profileRecords, pending, lastSync, savedDraft] = await Promise.all([
    recordPage(space, 50, stack.at(-1)),
    recordsBetween(space, now - 32 * 86_400_000, now + 32 * 86_400_000),
    recordsOfKind(space, 'member'), pendingCount(space), getMeta<number>('last-sync', space), getMeta<Draft>('draft', space),
  ]);
  if (!drafts.has(space)) drafts.set(space, savedDraft ?? emptyDraft());
  const records = [...new Map([...page.rows, ...monthRecords].map((r) => [r.id, r])).values()];
  const contextRecords = [...profileRecords, ...await decisionsFor(space, records.map((r) => r.id))];
  if (generation !== renderGeneration || activeSpace !== space) return;
  const profiles = new Map<string, MemberProfile>();
  const decisions = new Map<string, CategoryDecision>();
  const notes = new Map<string, NoteDecision>();
  for (const record of contextRecords) {
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
    if (
      recordKind === 'note' && value.kind === 'note' &&
      typeof value.target === 'string' && typeof value.note === 'string'
    ) {
      notes.set(value.target, record.value as NoteDecision);
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
      // Whoever wrote the note, not whose row it is: on a shared ledger anybody may write one,
      // so the words need the name that goes with them. Blank when it is this phone's own.
      const written = notes.get(record.id);
      const author = written?.editedByMemberId ?? '';
      return {
        id: record.id,
        ownerId,
        entry,
        categoryKind: category?.categoryKind || entry.categoryKind,
        ownerName: profiles.get(ownerId)?.name || (ownerId === session.member ? session.name : 'عضو خانواده'),
        categoryName: category?.categoryName || entry.categoryName || 'دسته‌بندی نشده',
        note: written?.note ?? '',
        noteAuthor: author && author !== session.member
          ? profiles.get(author)?.name || 'عضو خانواده'
          : '',
      };
    })
    .sort((a, b) => b.entry.at - a.entry.at);

  // The hero says «این ماه», so it sums this Jalali month and nothing else — the list below
  // still carries everything.
  const monthRows = rows.filter((r) => inJalaliMonth(r.entry.at, now) && !r.entry.transfer && r.categoryKind !== 'transfer');
  const visibleIds = new Set(page.rows.map((r) => r.id));
  const visibleRows = rows.filter((r) => visibleIds.has(r.id));
  const income = monthRows.filter((r) => r.entry.direction === 'in').reduce((s, r) => s + r.entry.amountRial, 0);
  const spent = monthRows.filter((r) => r.entry.direction === 'out').reduce((s, r) => s + r.entry.amountRial, 0);
  const members = [...profiles.values()].sort((a, b) => a.name.localeCompare(b.name, 'fa'));
  const [net, earned, paid] = [toman(income - spent), toman(income), toman(spent)];

  app.replaceChildren(
    el(`
      <div class="page">
        <div class="topbar">
          <span class="who">${PERSON}</span>
          <span class="name">سلام، ${escape(session.name)}</span>
        </div>
        <header class="hero">
          <!-- The figure under this label is a net and can come out negative, and bright green
               is what this app says "more" in. The word is what keeps the two from arguing:
               «مانده» is a balance, and a balance is allowed to be either sign. -->
          <p class="hero-label">مانده این ماه</p>
          <div class="figure total" style="--fit:${fit(net, 1.3)}">${net}<span class="unit">تومان</span></div>
          ${note ? `<p class="strip${warn ? ' warn' : ''}" role="status">${escape(note)}</p>` : ''}
          <p id="sync-status" class="muted" role="status" aria-live="polite">${syncing.has(space) ? 'در حال همگام‌سازی...' : pending ? `${fa(pending)} تغییر روی این مرورگر ذخیره شده و هنوز فرستاده نشده.` : lastSync ? `آخرین همگام‌سازی: ${new Date(lastSync).toLocaleString('fa-IR')}` : 'هنوز همگام نشده.'}</p>
        </header>
        <div class="actions">
          <button id="sync" class="action" type="button" ${syncing.has(space) ? 'disabled aria-busy="true"' : ''}>
            <span class="disc">${REFRESH}</span>
            همگام کن
          </button>
          <button id="households" type="button">خانواده‌ها</button>
        </div>
        <div class="stack">
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
            ${members.length ? `
              <p class="muted" style="margin-top:var(--l)">
                خانواده: ${members.map((m) => escape(m.name)).join('، ')}
              </p>` : ''}
          </div>
          <div class="panel">
            <p class="panel-title">پیامک بانکی</p>
            <label for="paste">متن پیامک رو اینجا بچسبون</label>
            <textarea id="paste" rows="3"></textarea>
            <button id="read" type="button">خواندن پیامک</button>
          </div>
          <form id="manual" class="panel" novalidate>
            <p class="panel-title">ثبت دستی</p>
            <label for="amount">مبلغ (تومان)</label>
            <input id="amount" inputmode="numeric" enterkeyhint="next" required />
            <label for="direction">نوع تراکنش</label>
            <select id="direction"><option value="out">خرج</option><option value="in">درآمد</option></select>
            <label for="merchant">بابت چه چیزی؟</label>
            <input id="merchant" enterkeyhint="done" maxlength="120" />
            <button id="save" class="primary" type="submit" ${submitting.has(space) ? 'disabled' : ''}>ثبت</button>
            <button id="cancel-edit" type="button" hidden>لغو ویرایش</button>
            <p id="save-error" class="error" role="alert"></p>
          </form>
        </div>
        <div class="section">
          <h2>تراکنش‌ها</h2>
          ${visibleRows.length ? `<span class="muted">${fa(visibleRows.length)} مورد در این صفحه</span>` : ''}
        </div>
        ${visibleRows.length ? `
          <div class="band" id="rows">
            ${visibleRows.map((r) => `
              <div class="row">
                <div class="grow">
                  <div>${escape(r.entry.merchant || 'بدون نام')}</div>
                  <div class="muted">${escape(r.ownerName)} • ${escape(r.categoryName)}</div>
                  ${r.note ? `<div class="muted">${escape(r.note)}${
                    r.noteAuthor ? ` — ${escape(r.noteAuthor)}` : ''
                  }</div>` : ''}
                  ${r.ownerId === session.member && r.entry.sourceKind === 'manual' ? `<button type="button" data-edit="${escape(r.id)}">ویرایش</button><button type="button" data-delete="${escape(r.id)}">حذف</button>` : ''}
                </div>
                <div class="figure amount ${r.entry.direction === 'in' ? 'in' : ''}">
                  ${r.entry.direction === 'in' ? '+' : '−'}${toman(r.entry.amountRial)}
                </div>
              </div>`).join('')}
          </div>` : `
          <div class="band" id="rows">
            <div class="empty">
              <span class="disc">${RECEIPT}</span>
              <p>هنوز تراکنشی ثبت نشده</p>
              <p class="muted">اولین چیزی که ثبت کنی، همین‌جا می‌مونه.</p>
            </div>
          </div>`}
        <nav aria-label="صفحه‌های تراکنش">${stack.length ? '<button id="previous-page" type="button">جدیدتر</button>' : ''}${page.next ? '<button id="next-page" type="button">قدیمی‌تر</button>' : ''}</nav>
      </div>
    `),
  );

  const amount = app.querySelector<HTMLInputElement>('#amount')!;
  const direction = app.querySelector<HTMLSelectElement>('#direction')!;
  const merchant = app.querySelector<HTMLInputElement>('#merchant')!;
  const paste = app.querySelector<HTMLTextAreaElement>('#paste')!;
  const draft = drafts.get(space)!;
  amount.value = draft.amount; direction.value = draft.direction; merchant.value = draft.merchant; paste.value = draft.paste;
  for (const field of [amount, direction, merchant, paste]) field.disabled = submitting.has(space);
  const saveButton = app.querySelector<HTMLButtonElement>('#save')!;
  saveButton.textContent = draft.editing ? 'ذخیره ویرایش' : 'ثبت';
  app.querySelector<HTMLButtonElement>('#cancel-edit')!.hidden = !draft.editing;
  const remember = (): void => {
    Object.assign(draft, { amount: amount.value, direction: direction.value, merchant: merchant.value, paste: paste.value });
    void setMeta('draft', draft, space).catch(() => { app.querySelector('#save-error')!.textContent = 'پیش‌نویس ذخیره نشد. قبل از بستن صفحه دوباره امتحان کن.'; });
  };
  for (const field of [amount, direction, merchant, paste]) field.addEventListener('input', remember);
  app.querySelector('#read')!.addEventListener('click', () => {
    const read = parsePasted(paste.value);
    if (read.amountRial != null) {
      amount.value = String(Math.trunc(read.amountRial / 10));
      draft.pastedAmount = amount.value; draft.pastedRial = read.amountRial;
    }
    if (read.direction) direction.value = read.direction;
    remember(); amount.focus();
  });
  app.querySelector('#manual')!.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (submitting.has(space)) return;
    const value = draft.pastedAmount === amount.value && draft.pastedRial != null ? draft.pastedRial : parseToman(amount.value);
    const failure = app.querySelector('#save-error')!;
    if (value == null) { failure.textContent = 'مبلغ رو با رقم کامل و بدون علامت یا اعشار بنویس.'; amount.focus(); return; }
    draft.localId ??= uuid7();
    remember(); submitting.add(space); saveButton.disabled = true;
    for (const field of [amount, direction, merchant, paste]) field.disabled = true;
    try {
      const previous = draft.editing ? records.find((r) => r.id === draft.editing)?.value as Entry | undefined : undefined;
      await save(session, {
        ...previous, at: draft.at ?? Date.now(), amountRial: value,
        direction: direction.value as 'in' | 'out', bank: previous?.bank ?? 'MANUAL',
        categoryId: previous?.categoryId ?? '', categoryName: previous?.categoryName ?? '',
        categoryKind: previous?.categoryKind ?? (direction.value === 'in' ? 'income' : 'expense'),
        categoryUpdatedAt: previous?.categoryUpdatedAt ?? 0, merchant: merchant.value.trim(),
      }, draft.localId, draft.editing);
      drafts.set(space, emptyDraft()); await setMeta('draft', emptyDraft(), space).catch(() => {});
      submitting.delete(space);
      if (activeSpace === space) await renderLedger(session, 'ثبت شد.');
      void trySync(session);
    } catch {
      failure.textContent = 'ذخیره نشد. نوشته‌ات همین‌جاست؛ دوباره امتحان کن.';
    } finally {
      submitting.delete(space); saveButton.disabled = false;
      for (const field of [amount, direction, merchant, paste]) field.disabled = false;
    }
  });
  app.querySelector('#cancel-edit')!.addEventListener('click', async () => {
    drafts.set(space, emptyDraft()); await setMeta('draft', emptyDraft(), space); await renderLedger(session);
  });
  app.querySelectorAll<HTMLButtonElement>('[data-edit]').forEach((button) => button.addEventListener('click', async () => {
    const row = visibleRows.find((r) => r.id === button.dataset.edit)!;
    drafts.set(space, { amount: String(Math.trunc(row.entry.amountRial / 10)), direction: row.entry.direction,
      merchant: row.entry.merchant, paste: '', editing: row.id, at: row.entry.at,
      pastedAmount: String(Math.trunc(row.entry.amountRial / 10)), pastedRial: row.entry.amountRial });
    await setMeta('draft', drafts.get(space), space); await renderLedger(session); app.querySelector<HTMLInputElement>('#amount')?.focus();
  }));
  app.querySelectorAll<HTMLButtonElement>('[data-delete]').forEach((button) => button.addEventListener('click', async () => {
    if (button.disabled || !confirm('این تراکنش حذف بشه؟')) return;
    button.disabled = true;
    try { await remove(session, button.dataset.delete!); await renderLedger(session, 'حذف شد.'); void trySync(session); }
    catch { button.disabled = false; app.querySelector('#save-error')!.textContent = 'حذف نشد. دوباره امتحان کن.'; }
  }));
  app.querySelector('#next-page')?.addEventListener('click', () => { pages.set(space, [...stack, page.next!]); void renderLedger(session); });
  app.querySelector('#previous-page')?.addEventListener('click', () => { pages.set(space, stack.slice(0, -1)); void renderLedger(session); });
  app.querySelector('#sync')!.addEventListener('click', () => void trySync(session, true));
  app.querySelector('#households')!.addEventListener('click', () => void renderHouseholds(session));
  if (focused) {
    const field = app.querySelector<HTMLInputElement | HTMLTextAreaElement>(`#${focused.id}`);
    field?.focus(); if (field && focused.start != null) field.setSelectionRange(focused.start, focused.end);
  }
}

async function renderHouseholds(current: Session): Promise<void> {
  const sessions = await savedSessions<Session>();
  renderGeneration++;
  app.replaceChildren(el(`<div class="page"><h1 class="title">خانواده‌ها</h1><p class="muted">دفترها و تغییرات نفرستاده جدا نگه داشته می‌شن. برای خانواده تازه، لینک دعوتش رو باز کن.</p><div id="sessions" class="stack"></div><button id="back" type="button">برگشت</button></div>`));
  for (const [index, session] of sessions.entries()) {
    const count = await pendingCount(partition(session));
    const button = el(`<button type="button">خانواده ${fa(index + 1)} • ${escape(session.name)}${count ? ` • ${fa(count)} تغییر نفرستاده` : ''}</button>`);
    button.addEventListener('click', async () => { await activateSession(session); await renderLedger(session); void trySync(session); });
    app.querySelector('#sessions')!.append(button);
  }
  app.querySelector('#back')!.addEventListener('click', () => void renderLedger(current));
}

async function trySync(session: Session, loud = false): Promise<void> {
  const space = partition(session);
  if (syncing.has(space)) return;
  syncing.add(space);
  const button = app.querySelector<HTMLButtonElement>('#sync');
  if (activeSpace === space) { button?.setAttribute('aria-busy', 'true'); if (button) button.disabled = true; }
  let message: string | undefined; let warn = false;
  try {
    const { sent, received } = await syncNow(session);
    if (loud) message = `${fa(sent)} مورد فرستادیم، ${fa(received)} مورد گرفتیم.`;
  } catch {
    message = 'اتصال نشد. تغییرات روی این مرورگر ذخیره شدن و بعداً فرستاده می‌شن.'; warn = true;
  } finally {
    syncing.delete(space);
    if (activeSpace === space && app.querySelector('#manual')) await renderLedger(session, message, warn);
  }
}

async function start(): Promise<void> {
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  try {
    const pairing = readPairing(location.hash);
    if (pairing) {
      const originalHash = location.hash;
      history.replaceState(null, '', location.pathname);
      renderJoin(pairing, originalHash, await loadSession());
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

window.addEventListener('online', () => { void loadSession().then((session) => { if (session && partition(session) === activeSpace) void trySync(session); }); });
void start();
