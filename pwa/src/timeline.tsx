/**
 * The activity timeline — what happened, grouped by the day it happened in Tehran (Timeline.kt).
 * The «دفتر» tab, one transaction's page, the review deck, and the two sheets they open.
 *
 * Every row traces back to the message it came from, because "every number can be traced back
 * to a transaction" is either true of every row on the screen or it is not true at all.
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks';
import './timeline.css';
import { useLedger, ledger } from './derived';
import type { LedgerView } from './derived';
import { categoriseAll, categorise, deleteTxn, restoreTxn, setManualTxnDay, setNote } from './ledger';
import { autoFilePlan } from './filing';
import { CAT_INSTALMENT, MAX_NOTE_CHARS, categoryChoices } from './rules';
import { bankFa } from './sms';
import { faLetters } from './catalog';
import { CategoryIcon, customGlyphs, glyphOf, hueCss } from './categoryIcon';
import type { CategoryGlyph } from './categoryIcon';
import { CategoryGrid } from './categoryGrid';
import { InstallmentLinkRow, WorthItCard, openInstallmentLink } from './budgetUi';
import { installmentPaidBy, installmentPayable } from './installments';
import { largeSpendThreshold, worthItAnswers, worthItCandidates } from './goals';
import { readPlans, answerWorthIt } from './plans';
import { currentMonthReport, reportMonthOf } from './reports';
import { tehranDay, tehranDayStart } from './jalali';
import {
  bidi, faCompact, faDay, faDayMoment, faMoment, faNumber, faSignedCompact, faSignedParts, faWordsToman, tomanOf,
} from './format';
import { closePage, closeSheet, openPage, openSheet, registerPage, registerSheet, registerTab, showNotice } from './nav';
import { row, rows } from './state';
import { DayStepper } from './manualTxn';
import { CheckMark, PlusMark, SearchMark } from './icons';
import { HeroPanel, PillButton, Screen, ScreenTitle, Sheet, SheetDelete, SheetTitle, SegmentedChoice, Switch } from './ui';
import type { Category, LedgerEntry, Txn } from './model';

// ---- words ------------------------------------------------------------------------------------

/**
 * What one transaction is called wherever it is named on its own: the merchant, or the bank that
 * reported it when the message named nobody. One function, so the row, the hero and the deck can
 * never call the same thing two different names.
 */
export function txnTitleFa(txn: Txn): string {
  return txn.merchant.trim() || (txn.sourceKind === 'manual' ? 'مورد دستی' : bankFa(txn.bank));
}

/** What the row prints on its category line — the transfer override included, so a search for
 * «انتقال» finds what a search of the visible words should find. */
export const ledgerCategoryFa = (entry: LedgerEntry): string =>
  entry.transfer ? 'انتقال بین حساب‌ها' : entry.categoryFa;

/**
 * One string folded for searching the ledger, the same folding on both sides of the match: one
 * digit set, ZWNJ and spaces gone («اسنپ‌فود», «اسنپ فود», «اسنپفود» are one merchant), the
 * Arabic ي/ك a bank's system spells folded to hers, latin lowercased.
 */
export function searchFold(s: string): string {
  let out = '';
  for (const c of faLetters(s)) {
    const code = c.charCodeAt(0);
    if (code >= 0x6f0 && code <= 0x6f9) out += String(code - 0x6f0);
    else if (code >= 0x660 && code <= 0x669) out += String(code - 0x660);
    else if (c === '‌' || /\s/.test(c)) continue;
    else out += c.toLowerCase();
  }
  return out;
}

/**
 * Does this row answer to what she typed? Searched over what the row shows — title, category line,
 * note, the bank behind it, whose it is, the bank's reference — and over the amount's Rial digits,
 * which contain the Toman digits as a prefix, so either way she remembers the figure lands.
 */
export function matchesLedgerSearch(entry: LedgerEntry, query: string): boolean {
  const q = searchFold(query);
  if (!q) return true;
  if (searchFold(txnTitleFa(entry.txn)).includes(q)) return true;
  if (searchFold(ledgerCategoryFa(entry)).includes(q)) return true;
  if (entry.note.trim() && searchFold(entry.note).includes(q)) return true;
  // Not on manual rows: their bank is the MANUAL placeholder, and matching its fallback name
  // would sweep every hand-entered row into an unrelated search.
  if (entry.txn.sourceKind !== 'manual' && searchFold(bankFa(entry.txn.bank)).includes(q)) return true;
  if (entry.ownerName.trim() && searchFold(entry.ownerName).includes(q)) return true;
  if (entry.txn.refNo.trim() && searchFold(entry.txn.refNo).includes(q)) return true;
  const digits = q.replace(/[^0-9]/g, '');
  return digits !== '' && entry.txn.amountRial != null && String(entry.txn.amountRial).includes(digits);
}

/**
 * What a start leaves out of دفتر and where it comes back. The room's name is held together with a
 * word joiner, so «وضعیت» and «دفتر» never land on two lines of a centred note.
 */
export const setAsideFa = (startsOn: number): string =>
  `تراکنش‌های قبل از ${reportMonthOf(startsOn).fa} کنار گذاشته شدن؛ از «وضعیت ⁠دفتر» توی تنظیمات برمی‌گردن.`;

/**
 * Which side of the ledger she is looking at. A transfer leg is neither income nor spending, and a
 * row with no amount is a stated balance — both appear under «همه» and nowhere else.
 */
type Lens = 'ALL' | 'INCOME' | 'EXPENSE';
const LENSES: Lens[] = ['ALL', 'INCOME', 'EXPENSE'];
const LENS_FA: Record<Lens, string> = { ALL: 'همه', INCOME: 'درآمد', EXPENSE: 'خرج' };
const LENS_EMPTY_FA: Record<Lens, string> = { ALL: '', INCOME: 'هنوز درآمدی ثبت نشده', EXPENSE: 'هنوز خرجی ثبت نشده' };
function lensMatches(lens: Lens, entry: LedgerEntry): boolean {
  if (lens === 'ALL') return true;
  if (entry.transfer) return false;
  const signed = entry.txn.signedRial;
  if (signed == null) return false;
  return lens === 'INCOME' ? signed > 0 : signed < 0;
}

/** Everything the app knows about one transaction that is not its figure — one list for the page and the deck. */
function transactionDetails(entry: LedgerEntry): Array<[string, string]> {
  const txn = entry.txn;
  const out: Array<[string, string]> = [['نوع', txn.direction === 'in' ? 'واریز' : txn.direction === 'out' ? 'برداشت' : 'نامشخص']];
  if (entry.ownerName.trim()) out.push(['صاحب تراکنش', entry.ownerName]);
  if (entry.categoryEditorName.trim()) out.push(['دسته‌بندی توسط', entry.categoryEditorName]);
  // The hero says «امروز»; the written-out date and its minute belong here. «زمان ثبت» is a
  // different fact: the stamp the bank printed, often a bare date.
  out.push(['تاریخ', faMoment(txn.at, txn.day)]);
  out.push(['ثبت شده از', txn.sourceKind === 'manual' ? 'ورود دستی' : bankFa(txn.bank)]);
  if (txn.printedAt.trim()) out.push(['زمان ثبت', bidi(txn.printedAt)]);
  if (txn.mask.trim()) out.push(['کارت یا حساب', bidi(txn.mask)]);
  if (txn.refNo.trim()) out.push(['شماره پیگیری', bidi(txn.refNo)]);
  if (txn.balanceRial != null) out.push(['مانده بعد از تراکنش', bidi(`${faCompact(tomanOf(txn.balanceRial))} تومان`)]);
  if (txn.feeRial != null && txn.feeRial > 0) out.push(['کارمزد', bidi(`${faCompact(tomanOf(txn.feeRial))} تومان`)]);
  return out;
}

/**
 * The message the row was read out of, or the reason it is not here. `txn.ownerMemberId` (not the
 * entry's) is blank unless the row arrived from somebody else's device — the only case where the
 * body genuinely is not here. A pasted body is in memory, so there is no «still reading» state.
 */
function sourceText(entry: LedgerEntry): string {
  if (entry.txn.sourceKind === 'manual') return 'این مورد دستی ثبت شده.';
  if (entry.txn.ownerMemberId.trim()) return `متن خام پیامک فقط روی گوشی ${entry.ownerName.trim() || 'صاحب تراکنش'} نگه داشته می‌شه.`;
  return row('sources', entry.txn.srcHash)?.body || 'متن پیامک اصلی پیدا نشد.';
}

/**
 * What «از این به بعد» will actually key on, in her words: a merchant when the message named one,
 * otherwise the sender, bank and channel together — a far wider net, and «همیشه» must never be a
 * surprise afterwards.
 */
function learnedRule(txn: Txn): string {
  if (txn.merchant.trim()) {
    return `«از این به بعد» یعنی هر تراکنش دیگه‌ای از «${txn.merchant}» هم خودکار همین دسته می‌شه، و مشابه‌های قبلی هم اصلاح می‌شن.`;
  }
  const kind = txn.direction === 'in' ? 'واریزهای' : txn.direction === 'out' ? 'برداشت‌های' : 'تراکنش‌های';
  return `این پیامک اسم فروشنده نداره، پس «از این به بعد» روی ${kind} شبیه این از ${bankFa(txn.bank)} اعمال می‌شه، و مشابه‌های قبلی هم اصلاح می‌شن.`;
}

// ---- shared state and lookups -------------------------------------------------------------------

/** The page of one transaction, by ref — the one way in, so nobody else spells the prop. */
export const openTxn = (ref: string): void => openPage('txn', { txnRef: ref });

/**
 * The Tehran day it is right now, kept true across midnight: a timer to the next midnight, and a
 * recheck when the page is shown again — a phone asleep at midnight fires late.
 */
export function useTehranDay(): number {
  const [day, setDay] = useState(() => tehranDay(Date.now()));
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const arm = (): void => {
      clearTimeout(timer);
      const now = Date.now();
      setDay(tehranDay(now));
      // The floor is for a clock yanked backwards, where an instant re-fire would spin.
      timer = setTimeout(arm, Math.max(tehranDayStart(tehranDay(now) + 1) - now, 1000));
    };
    arm();
    const shown = (): void => { if (document.visibilityState === 'visible') arm(); };
    document.addEventListener('visibilitychange', shown);
    return () => { clearTimeout(timer); document.removeEventListener('visibilitychange', shown); };
  }, []);
  return day;
}

/**
 * A page opens at its own top and hands back the screen under it where she left it: the window's
 * scroll is shared by every tab and page, so without this a transaction opened from two hundred
 * rows down would open two hundred rows down, and the ledger would come back at the top.
 */
export function usePageTop(): void {
  useLayoutEffect(() => {
    const was = scrollY;
    scrollTo(0, 0);
    // After the commit that shows the screen underneath again, before it paints.
    return () => queueMicrotask(() => scrollTo(0, was));
  }, []);
}

/** The marks she gave her categories, once per derived view rather than once per row. */
const glyphMemo = new WeakMap<LedgerView, Record<string, CategoryGlyph>>();
function glyphsOf(view: LedgerView): Record<string, CategoryGlyph> {
  let glyphs = glyphMemo.get(view);
  if (!glyphs) glyphMemo.set(view, (glyphs = customGlyphs(view.managedCategories)));
  return glyphs;
}

/**
 * Which categories she has narrowed the ledger to — session state, like the lens: a filter that
 * survived a week would be a week of «where did my money go». Held here rather than in the screen
 * because the sheet that edits it lives in the sheet slot, and every tap there lands live.
 */
let catFilter: readonly string[] = [];
const filterListeners = new Set<() => void>();
function setCatFilter(next: readonly string[]): void {
  catFilter = next;
  for (const l of filterListeners) l();
}
function useCatFilter(): readonly string[] {
  const [, set] = useState(0);
  useEffect(() => {
    const l = (): void => set((n) => n + 1);
    filterListeners.add(l);
    return () => { filterListeners.delete(l); };
  }, []);
  return catFilter;
}

/** Every category the ledger actually holds rows under, in the picker's order — no dead options. */
function filterOptionsOf(everything: LedgerEntry[], view: LedgerView): Array<[string, string]> {
  const used = new Set(everything.map((e) => e.categoryId));
  const known = view.categories.filter((c) => used.has(c.id));
  const knownIds = new Set(known.map((c) => c.id));
  const leftover = [...used].filter((id) => !knownIds.has(id))
    .map((id): [string, string] => [id, everything.find((e) => e.categoryId === id)!.categoryFa]);
  return [...known.map((c): [string, string] => [c.id, c.nameFa]), ...leftover];
}

// ---- دفتر ---------------------------------------------------------------------------------------

function TimelineScreen() {
  const view = useLedger();
  const today = useTehranDay();
  const [lens, setLens] = useState<Lens>('ALL');
  const filter = useCatFilter();
  // Two states: closing the field must not silently drop the narrowing — the line under the
  // controls is what names it.
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  // «Nothing here at all» and «nothing on this side» are different facts with different answers.
  const everything = useMemo(() => view.entries.filter((e) => !e.duplicate), [view.entries]);
  const visible = useMemo(
    () => everything.filter((e) => lensMatches(lens, e) && (!filter.length || filter.includes(e.categoryId)) && matchesLedgerSearch(e, query)),
    [everything, lens, filter, query],
  );
  const grouped = useMemo(() => {
    const days = new Map<number, LedgerEntry[]>();
    for (const e of visible) {
      const list = days.get(e.txn.day);
      if (list) list.push(e); else days.set(e.txn.day, [e]);
    }
    return [...days].sort((a, b) => b[0] - a[0]);
  }, [visible]);
  const waiting = view.review.length;

  // The newest of what she just asked for, not wherever the old offset landed — but only once she
  // actually narrowed something; the first pass is the tab coming back with its place kept.
  const narrowed = useRef(false);
  useEffect(() => {
    if (!narrowed.current) narrowed.current = true;
    else if (visible.length) scrollTo(0, 0);
  }, [lens, filter, query]);

  const filterNames = filter.length
    ? filterOptionsOf(everything, view).filter(([id]) => filter.includes(id)).map(([, name]) => name).join('، ')
    : '';

  return (
    <Screen>
      {/* Pinned: a filter she cannot see is a filter she forgets she set, and on this screen
          that reads as money gone missing. */}
      <div class="ledger-top">
        <div class={`ledger-head${waiting > 0 ? ' crowded' : ''}`}>
          <ScreenTitle>دفتر</ScreenTitle>
          <AddTxnButton />
          <PasteButton />
          {waiting > 0 && (
            <button type="button" class="pill primary ledger-pill" onClick={() => openPage('deck')}>
              {`مرور ${faNumber(waiting)} مورد`}
            </button>
          )}
        </div>
        {everything.length > 0 && (
          <>
            <div class="ledger-controls">
              <div class="grow">
                <SegmentedChoice options={LENSES} selected={lens} label={(l) => LENS_FA[l]} onSelect={setLens} fontSize={14} />
              </div>
              <button type="button" class={`ledger-disc${searching || query.trim() ? ' active' : ''}`} aria-label="جستجو"
                aria-pressed={searching} onClick={() => setSearching(!searching)}>
                <SearchMark />
              </button>
              <button type="button" class={`pill ledger-pill${filter.length ? ' active' : ''}`} onClick={() => openSheet('categoryFilter')}>
                {filter.length ? `دسته‌ها • ${faNumber(filter.length)}` : 'دسته‌ها'}
              </button>
            </div>
            {searching && <SearchField query={query} onQuery={setQuery} />}
            {query.trim() && <NarrowedLine words={`فقط نتیجه‌های «${bidi(query.trim())}»`} onClear={() => setQuery('')} />}
            {filter.length > 0 && <NarrowedLine words={`فقط ${filterNames}`} onClear={() => setCatFilter([])} />}
          </>
        )}
      </div>

      {everything.length === 0 ? <EmptyLedger startsOn={view.health.startsOn} />
        : !visible.length ? (
          // The words first: they are the narrowing she touched last.
          query.trim() ? <Answer title={`برای «${bidi(query.trim())}» چیزی پیدا نشد`} action="پاک کردن جستجو" onAction={() => setQuery('')} />
            : filter.length ? <Answer title="با این فیلتر چیزی اینجا نیست" action="پاک کردن فیلتر" onAction={() => setCatFilter([])} />
              : <Answer title={LENS_EMPTY_FA[lens]} action="همه رو نشون بده" onAction={() => setLens('ALL')} />
        ) : (
          <div class="ledger-list">
            {grouped.map(([day, entries]) => (
              <section key={day}>
                <DayHeading day={day} today={today} rows={entries} />
                {entries.map((e) => <TimelineRow key={e.txn.ref} entry={e} onClick={() => openTxn(e.txn.ref)} />)}
              </section>
            ))}
            {/* Where the list stops because she chose where it starts — a ledger that simply
                ends reads as money gone missing. */}
            {view.health.startsOn > 0 && <p class="ledger-aside">{setAsideFa(view.health.startsOn)}</p>}
          </div>
        )}
    </Screen>
  );
}

/**
 * The door to a hand-entered transaction. It says the word — a bare «+» is a guess she spends a
 * tap to check — in the chips' neutral well, the mark alone carrying the interactive colour.
 * On a crowded line the words yield first; the review count never does.
 */
function AddTxnButton() {
  return (
    <button type="button" class="pill ledger-pill add" aria-label="تراکنش دستی" onClick={() => openSheet('manualTxn')}>
      <PlusMark color="var(--primary)" />
      <span class="word">تراکنش</span>
    </button>
  );
}

/** The browser's one door the phone does not need: a bank message she pastes, since nothing here can read her inbox. */
function PasteButton() {
  return (
    <button type="button" class="pill ledger-pill add paste" aria-label="پیامک" onClick={() => openSheet('pasteSms')}>
      <MessageMark />
      <span class="word">پیامک</span>
    </button>
  );
}

/** A speech bubble in the one pen the plus and the magnifier are drawn with. */
function MessageMark() {
  return (
    <svg width={20} height={20} viewBox="0 0 24 24" aria-hidden="true" style={{ flex: 'none', display: 'block' }}>
      <path fill="none" stroke="var(--primary)" stroke-width={2.6} stroke-linecap="round" stroke-linejoin="round"
        d="M4 6.5a2.5 2.5 0 0 1 2.5-2.5h11a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-6.5l-5 4v-4a2.5 2.5 0 0 1-2-2.5z" />
    </svg>
  );
}

function SearchField({ query, onQuery }: { query: string; onQuery: (q: string) => void }) {
  const input = useRef<HTMLInputElement>(null);
  // She opened it to type, so the caret goes where the typing goes.
  useEffect(() => { input.current?.focus(); }, []);
  return (
    <label class="field ledger-search">
      <input ref={input} enterKeyHint="search" value={query} placeholder="جستجو…" aria-label="جستجوی دفتر"
        onInput={(e) => onQuery((e.currentTarget as HTMLInputElement).value)} />
    </label>
  );
}

/** One narrowing named where it acts, with the one-tap way back beside the sentence it undoes. */
function NarrowedLine({ words, onClear }: { words: string; onClear: () => void }) {
  return (
    <div class="narrowed">
      <p class="grow">{words}</p>
      {/* A 40px pill on a 48px target: the line earns the small pill, a thumb does not. */}
      <button type="button" class="clear-pill" onClick={onClear}><span>پاک کردن</span></button>
    </div>
  );
}

/** A narrowing that matched nothing — never «the ledger is empty», and always the way back. */
function Answer({ title, action, onAction }: { title: string; action: string; onAction: () => void }) {
  return (
    <div class="ledger-answer">
      <p class="strong">{title}</p>
      <PillButton label={action} onClick={onAction} />
    </div>
  );
}

/**
 * Nothing to show. When that is because of where she started the ledger it says so; otherwise it
 * says how a browser ledger fills — pasting, since nothing here reads her messages.
 */
function EmptyLedger({ startsOn }: { startsOn: number }) {
  return (
    <div class="ledger-answer">
      <p class="strong">{startsOn > 0 ? `از ${reportMonthOf(startsOn).fa} به بعد هنوز تراکنشی نیست` : 'هنوز هیچ تراکنشی نیست'}</p>
      <p class="muted small">
        {startsOn > 0 ? setAsideFa(startsOn) : 'پیامک بانکت رو کپی کن و با «پیامک» همین بالا بچسبون تا دفترت پر بشه.'}
      </p>
    </div>
  );
}

/**
 * A day's quiet heading, carrying the day's figure — summed over the rows under it so it can never
 * disagree with them, transfers left out, and only when it actually summarises more than one row.
 */
function DayHeading({ day, today, rows: dayRows }: { day: number; today: number; rows: LedgerEntry[] }) {
  const moved = dayRows.filter((e) => !e.transfer).map((e) => e.txn.signedRial).filter((r): r is number => r != null && r !== 0);
  const net = moved.reduce((a, b) => a + b, 0);
  return (
    <div class="day-heading">
      <h2 class="grow">{faDay(day, today)}</h2>
      {net !== 0 && moved.length > 1 && (
        <span class={`figure${net > 0 ? ' gain' : ' muted'}`}>{faSignedCompact(tomanOf(net), net > 0)}</span>
      )}
    </div>
  );
}

/** Whose row: the face they picked, their initial, or their photo (Family.kt MemberFace). */
export function MemberFace({ name, avatar, size = 44 }: { name: string; avatar: string; size?: number }) {
  const photo = avatar.startsWith('b64:');
  return (
    // The name is always said beside the face, so the face says nothing itself.
    <span class="member-face" aria-hidden="true" style={{ width: `${size}px`, height: `${size}px`, fontSize: `${size * (avatar && !photo ? 0.62 : 0.56)}px` }}>
      {photo ? <img src={`data:image/jpeg;base64,${avatar.slice(4)}`} alt="" />
        : avatar.trim() ? avatar
          : <b>{name.trim().charAt(0)}</b>}
    </span>
  );
}

/**
 * One transaction in the list: its category's mark in its category's hue — a waiting row stays
 * grey, which is the distinction the disc is for — whose it is on the disc's edge, the amount in
 * the ledger's in/out colours, and a transfer leg struck through because it is in no total.
 */
export function TimelineRow({ entry, showIcon = true, onClick }: { entry: LedgerEntry; showIcon?: boolean; onClick: () => void }) {
  const txn = entry.txn;
  const incoming = txn.direction === 'in';
  const categoryFa = ledgerCategoryFa(entry);
  const glyph = glyphOf(categoryFa, glyphsOf(ledger()));
  const hue = hueCss(glyph);
  const rial = txn.signedRial ?? txn.amountRial;
  const waiting = entry.needsReview && !entry.transfer;
  const owner = entry.ownerName.trim();
  // The amber dot is colour alone, so on a waiting row the whole line is restated in words.
  const pendingWords = waiting
    ? [txnTitleFa(txn), categoryFa, ...(owner ? [`از ${entry.ownerName}`] : []),
      rial != null ? `${faSignedCompact(tomanOf(rial), incoming)} تومان` : 'مانده', 'در انتظار دسته‌بندی'].join('، ')
    : undefined;
  return (
    <button type="button" class="txn-row" onClick={onClick} aria-label={pendingWords}>
      {showIcon && (
        <span class="txn-disc" style={{ '--hue': hue }}>
          <CategoryIcon glyph={glyph} size={22} stroke={1.8} color="var(--hue)" />
          {owner && <span class="owner-badge" aria-hidden="true"><MemberFace name={entry.ownerName} avatar={entry.ownerAvatar} size={21} /></span>}
        </span>
      )}
      <span class="grow txn-text">
        <span class="txn-title ellipsis">{txnTitleFa(txn)}</span>
        <span class="txn-line">
          <span class="ellipsis">{categoryFa}</span>
          {owner && <span class="txn-owner">{`از ${entry.ownerName}`}</span>}
          {/* A dot, not a warning: nothing is wrong, something is merely unconfirmed. */}
          {waiting && <span class="review-dot" />}
        </span>
      </span>
      {rial != null ? (
        <span class={`figure txn-amount${entry.transfer ? ' transfer' : incoming ? ' gain' : ''}`}>
          {faSignedCompact(tomanOf(rial), incoming)}
        </span>
      ) : <span class="muted txn-balance">مانده</span>}
    </button>
  );
}

/** One category as a pill: its own mark in its own hue, and `primary` when chosen. */
export function FilterCategoryChip({ name, chosen, onToggle }: { name: string; chosen: boolean; onToggle: () => void }) {
  const glyph = glyphOf(name, glyphsOf(ledger()));
  return (
    <button type="button" class="filter-chip" role="checkbox" aria-checked={chosen} onClick={onToggle}>
      <CategoryIcon glyph={glyph} size={16} color={chosen ? 'var(--on-primary)' : hueCss(glyph)} />
      {name}
    </button>
  );
}

/** Which categories the ledger shows, chosen live: every tap lands behind the scrim, so «بستن» is the only verb. */
function CategoryFilterSheet() {
  const view = useLedger();
  const selected = useCatFilter();
  const everything = view.entries.filter((e) => !e.duplicate);
  return (
    <Sheet label="کدوم دسته‌ها؟">
      <SheetTitle>کدوم دسته‌ها؟</SheetTitle>
      <p class="muted small" style={{ paddingTop: 'var(--s)' }}>هر چندتا که می‌خوای بزن؛ دفتر فقط همون‌ها رو نشون می‌ده.</p>
      <div class="filter-chips">
        {filterOptionsOf(everything, view).map(([id, name]) => (
          <FilterCategoryChip key={id} name={name} chosen={selected.includes(id)}
            onToggle={() => setCatFilter(selected.includes(id) ? selected.filter((s) => s !== id) : [...selected, id])} />
        ))}
      </div>
      <div class="sheet-actions">
        <PillButton label="بستن" voice="primary" block onClick={closeSheet} />
        {selected.length > 0 && <button type="button" class="pill wide" onClick={() => { setCatFilter([]); closeSheet(); }}>پاک کردن فیلتر</button>}
      </div>
    </Sheet>
  );
}

// ---- one transaction ------------------------------------------------------------------------------

/** «جزئیات», «منبع» — the one heading a band of a transaction sits under, on either screen. */
function SectionHeading({ children }: { children: ComponentChildren }) {
  return <h2 class="txn-section">{children}</h2>;
}

/**
 * The transaction, in the field the app keeps for a figure that stands on its own — in the
 * ledger's in/out colours, the words under it the only exact figure on the screen.
 */
function TransactionHero({ entry, backLabel = 'برگشت', onBack = closePage, note }: {
  entry: LedgerEntry; backLabel?: string; onBack?: () => void; note?: string;
}) {
  const txn = entry.txn;
  const incoming = txn.direction === 'in';
  const toman = txn.amountRial == null ? null : tomanOf(txn.amountRial);
  const [digits, magnitude] = toman == null ? ['', null] : faSignedParts(toman, incoming);
  const fit = Math.max(5, [...digits].length * 0.62 + (magnitude ? [...magnitude].length * 0.36 + 0.3 : 0) + 1.6);
  const words = toman == null ? null : faWordsToman(toman);
  return (
    <HeroPanel class="txn-hero">
      <div class="row-flex">
        <span class="grow muted txn-when">{faDayMoment(txn.at, txn.day)}</span>
        {note && <span class="muted figure small">{note}</span>}
        <PillButton label={backLabel} voice="hero" onClick={onBack} />
      </div>
      <h1 class="txn-hero-title">{txnTitleFa(txn)}</h1>
      {toman != null ? (
        <>
          {/* The sign travels with the digits; «میلیون» and «تومان» stay in the RTL line. */}
          <p class="figure txn-figure" style={{ color: incoming ? 'var(--hero-mint)' : 'var(--hero-strong)', '--fit': fit }}>
            {digits}
            {magnitude && <>{' '}<span class="magnitude">{magnitude}</span></>}
            {' '}<span class="unit">تومان</span>
          </p>
          {words && <p class="txn-words">{words}</p>}
        </>
      ) : <p class="txn-no-amount">مبلغ در پیامک مشخص نشده</p>}
    </HeroPanel>
  );
}

/**
 * «برای موارد مشابه هم همین دسته» — the one switch both filing surfaces share, off by default: a
 * standing rule is the exception she opts into. On, the caption is the rule's exact promise.
 */
function LearnSimilarToggle({ txn, checked, onChange }: { txn: Txn; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label class="learn-toggle">
      <span class="grow">
        <span class="title">برای موارد مشابه هم همین دسته</span>
        <span class="caption">{checked ? learnedRule(txn) : 'موارد قبلی و بعدی مشابه هم اصلاح می‌شن'}</span>
      </span>
      <Switch checked={checked} onChange={onChange} label="برای موارد مشابه هم همین دسته" />
    </label>
  );
}

/**
 * Words about the row, saved when she says so. The save pill exists only while the draft and the
 * stored note disagree; its disappearance is the receipt. Somebody else's note says whose it is.
 */
function NoteField({ entry }: { entry: LedgerEntry }) {
  const [draft, setDraft] = useState(entry.note);
  return (
    <div>
      <label class="field">
        <textarea rows={2} value={draft} maxLength={MAX_NOTE_CHARS} placeholder="مثلاً کادوی تولد مامان" aria-label="یادداشت تراکنش"
          onInput={(e) => setDraft((e.currentTarget as HTMLTextAreaElement).value.slice(0, MAX_NOTE_CHARS))} />
      </label>
      {entry.noteAuthorName.trim() && <p class="muted small" style={{ marginTop: 'var(--s)' }}>{`یادداشت از ${entry.noteAuthorName}`}</p>}
      {draft.trim() !== entry.note && (
        <div style={{ marginTop: 'var(--s)' }}><PillButton label="ذخیره یادداشت" voice="primary" onClick={() => setNote(entry, draft)} /></div>
      )}
    </div>
  );
}

/**
 * The day a typed row is filed under, moved when she says so — nothing auto-saves, because a stray
 * tap on «روز قبل» must not move a transaction out of a report she has already read. `today` is
 * the live one: the page can be left open past midnight.
 */
function DayField({ entry }: { entry: LedgerEntry }) {
  const today = useTehranDay();
  const [draft, setDraft] = useState(entry.txn.day);
  // The ledger's own answer settles the draft once the save lands, or when another phone moved it.
  useEffect(() => setDraft(entry.txn.day), [entry.txn.day]);
  return (
    <div>
      <DayStepper day={draft} today={today} onDay={setDraft} />
      {draft !== entry.txn.day && (
        <div style={{ marginTop: 'var(--m)' }}><PillButton label="ذخیره تاریخ" voice="primary" onClick={() => setManualTxnDay(entry, draft)} /></div>
      )}
    </div>
  );
}

function DetailsPanel({ details }: { details: Array<[string, string]> }) {
  return (
    <div class="details">
      {details.map(([label, value]) => <div class="detail-row" key={label}><span>{label}</span><span>{value}</span></div>)}
    </div>
  );
}

/**
 * The message, in the deck: four lines and a tap for the rest — banks write with hard line breaks
 * and a grid already fills the screen. «متن کامل» appears only when the layout says it clipped.
 */
function SourcePreview({ entry }: { entry: LedgerEntry }) {
  const text = sourceText(entry);
  const [expanded, setExpanded] = useState(false);
  const [clipped, setClipped] = useState(false);
  const body = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    // Sticky: expanded, nothing overflows any more, and the way back must not vanish under her finger.
    const el = body.current;
    if (el && !expanded && el.scrollHeight > el.clientHeight + 1) setClipped(true);
  }, [text, expanded]);
  const inner = (
    <>
      <span ref={body} class={`source-text${expanded ? '' : ' clamp'}`}>{text}</span>
      {(clipped || expanded) && <span class="source-more">{expanded ? 'کوتاه‌تر' : 'متن کامل پیامک'}</span>}
    </>
  );
  return clipped || expanded
    ? <button type="button" class="panel source" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{inner}</button>
    : <div class="panel source">{inner}</div>;
}

/** The categories in her most-used order, held for as long as this transaction is up: a grid that
 * reshuffles between two taps is one she has to read again. Keyed on the categories' content,
 * because every derive hands over a new array of the same categories. */
function useChoices(entry: LedgerEntry, view: LedgerView) {
  const key = `${entry.txn.ref}|${view.categories.map((c) => `${c.id}:${c.nameFa}`).join(',')}`;
  const memo = useRef<{ key: string; choices: Category[] } | null>(null);
  if (memo.current?.key !== key) memo.current = { key, choices: categoryChoices(view.categories, entry.txn.direction, view.categoryUse) };
  return memo.current.choices;
}

function TransactionPage({ txnRef }: { txnRef: string }) {
  usePageTop();
  const view = useLedger();
  const live = view.entries.find((e) => e.txn.ref === txnRef);
  // The last sight of it, for the beat between a delete and the page closing.
  const last = useRef(live);
  if (live) last.current = live;
  const entry = live ?? last.current;
  if (!entry) return null;
  return <TransactionBody key={entry.txn.ref} entry={entry} view={view} />;
}

function TransactionBody({ entry, view }: { entry: LedgerEntry; view: LedgerView }) {
  const txn = entry.txn;
  const [learnSimilar, setLearnSimilar] = useState(false);
  const [chosen, setChosen] = useState(entry.categoryId);
  useEffect(() => setChosen(entry.categoryId), [entry.categoryId]);
  const choices = useChoices(entry, view);
  const payable = installmentPayable(entry, view.mineId);
  const paidPlan = useMemo(
    () => (payable ? installmentPaidBy(txn.ref, readPlans(view.allEntries, view.entries).installments) : null),
    [payable, view, txn.ref],
  );
  const own = txn.ref.startsWith('m:') || txn.ref.startsWith('s:');
  return (
    <div class="screen no-tabs txn-page">
      <TransactionHero entry={entry} />

      <SectionHeading>دسته‌بندی</SectionHeading>
      <p class="txn-caption">
        {entry.transfer ? 'این مورد انتقال تشخیص داده شده. با انتخاب یک دسته می‌تونی اصلاحش کنی.'
          : entry.needsReview ? 'دسته درست رو بزن تا این مورد تایید بشه.'
            : `دسته فعلی ${entry.categoryFa} است. برای تغییر، دسته تازه رو بزن.`}
      </p>
      <div style={{ margin: 'var(--m) 0 var(--l)' }}><LearnSimilarToggle txn={txn} checked={learnSimilar} onChange={setLearnSimilar} /></div>
      <CategoryGrid categories={choices} selected={chosen}
        onSelect={(category) => {
          setChosen(category.id);
          categorise(entry, category.id, learnSimilar);
          // Filed as a قسط, the next question is which one — asked while the payment is in front of her.
          if (category.id === CAT_INSTALMENT && payable && !paidPlan) openInstallmentLink(txn.ref);
        }}
        addTile={() => openSheet('category', { kind: txn.direction === 'in' ? 'income' : 'expense', grid: true })} />

      {payable && (chosen === CAT_INSTALMENT || paidPlan) && (
        <>
          <SectionHeading>قسط</SectionHeading>
          <div style={{ marginTop: 'var(--m)' }}><InstallmentLinkRow current={paidPlan} onOpen={() => openInstallmentLink(txn.ref)} /></div>
        </>
      )}

      <SectionHeading>یادداشت</SectionHeading>
      <div style={{ marginTop: 'var(--m)' }}><NoteField entry={entry} /></div>

      {/* Above «جزئیات», which states the date — the correction sits by what it corrects. Only on
          this browser's own typed rows: a message's day is the bank's stamp, re-read every derive. */}
      {txn.ref.startsWith('m:') && (
        <>
          <SectionHeading>تاریخ</SectionHeading>
          <p class="txn-caption">اگه روز اشتباهی ثبت شده، همین‌جا درستش کن. ساعتش همون که بود می‌مونه.</p>
          <div style={{ marginTop: 'var(--m)' }}><DayField entry={entry} /></div>
        </>
      )}

      <SectionHeading>جزئیات</SectionHeading>
      <div style={{ marginTop: 'var(--m)' }}><DetailsPanel details={transactionDetails(entry)} /></div>

      <SectionHeading>منبع</SectionHeading>
      {/* Whole and selectable here, where the message is the subject of the screen. */}
      <p class="panel source source-text" style={{ marginTop: 'var(--m)' }}>{sourceText(entry)}</p>

      {/* Only her own rows: another member's (`f:`) is theirs to delete. A pasted message stays
          stored either way — deleting hides what was made of it; it does not destroy evidence. */}
      {own && (
        <div style={{ marginTop: 'var(--xl)' }}>
          <SheetDelete label="حذف این تراکنش" onConfirmed={() => {
            const ref = txn.ref;
            deleteTxn(entry);
            closePage();
            // The undo is a second net under the two-tap arming, not a replacement.
            showNotice('تراکنش پاک شد', { label: 'برگردون', run: () => restoreTxn(ref) });
          }} />
        </div>
      )}
    </div>
  );
}

// ---- the review deck ------------------------------------------------------------------------------

/**
 * The exception deck: one card at a time, and a tap files it on the spot — the same gesture, switch
 * and default as the transaction page. «بمونه برای بعد» writes nothing at all: skipping must cost
 * her nothing and must not be recorded as an opinion she does not have.
 */
function ReviewDeck() {
  usePageTop();
  const view = useLedger();
  const [skipped, setSkipped] = useState<ReadonlySet<string>>(new Set());
  // Always the head of what is left: the deck is a countdown, not a carousel.
  const pending = view.review.filter((e) => !skipped.has(e.txn.ref));
  const entry = pending[0];
  if (!entry) return <DeckEnd view={view} />;
  return (
    <DeckCard key={entry.txn.ref} entry={entry} view={view} left={pending.length}
      onSkip={() => setSkipped(new Set([...skipped, entry.txn.ref]))}
      onAutoFile={() => openSheet('autoFile', { skipped: [...skipped] })} />
  );
}

function DeckCard({ entry, view, left, onSkip, onAutoFile }: {
  entry: LedgerEntry; view: LedgerView; left: number; onSkip: () => void; onAutoFile: () => void;
}) {
  const txn = entry.txn;
  // Off on every card: a switch that stayed on across cards would write rules she never read.
  const [learnSimilar, setLearnSimilar] = useState(false);
  const [picked, setPicked] = useState<string | null>(null);
  const choices = useChoices(entry, view);
  // A new card starts at its own top, instantly: a replacement, not a movement.
  useEffect(() => { scrollTo(0, 0); }, []);
  return (
    <div class="screen no-tabs deck">
      {/* What is left, not a position: the list shrinks, nothing walks through it. */}
      <TransactionHero entry={entry} backLabel="بستن" note={`${faNumber(left)} مونده`} />
      <h2 class="deck-question">
        {txn.direction === 'in' ? 'این واریز رو چی حساب کنم؟' : txn.direction === 'out' ? 'این خرج رو چی حساب کنم؟' : 'این تراکنش رو چی حساب کنم؟'}
      </h2>
      <div style={{ margin: 'var(--m) 0' }}><LearnSimilarToggle txn={txn} checked={learnSimilar} onChange={setLearnSimilar} /></div>
      <CategoryGrid categories={choices} selected={picked} selectedLabel="انتخاب‌شده"
        onSelect={(category) => {
          setPicked(category.id);
          categorise(entry, category.id, learnSimilar);
          if (category.id === CAT_INSTALMENT && installmentPayable(entry, view.mineId) &&
            !installmentPaidBy(txn.ref, readPlans(view.allEntries, view.entries).installments)) openInstallmentLink(txn.ref);
        }}
        addTile={() => openSheet('category', { kind: txn.direction === 'in' ? 'income' : 'expense', grid: true })} />

      <SectionHeading>یادداشت</SectionHeading>
      <div style={{ marginTop: 'var(--m)' }}><NoteField entry={entry} /></div>
      <SectionHeading>جزئیات</SectionHeading>
      <div style={{ marginTop: 'var(--m)' }}><DetailsPanel details={transactionDetails(entry)} /></div>
      <SectionHeading>منبع</SectionHeading>
      <div style={{ margin: 'var(--m) 0 var(--l)' }}><SourcePreview entry={entry} /></div>

      {/* The one answer that is always available does not scroll away. Beside it, while the
          backlog is real, the way out of the whole chore — behind a sheet that says what happens. */}
      <div class="deck-foot">
        <button type="button" class="deck-answer quiet" onClick={onSkip}>بمونه برای بعد</button>
        {left >= 5 && <button type="button" class="deck-answer secondary" onClick={onAutoFile}>خودکار برای همه</button>}
      </div>
    </div>
  );
}

/**
 * The deck with nothing left: the one optional question gets asked — at most two a week, about
 * something large she chose to buy — and otherwise the moment the whole screen exists to reach.
 */
function DeckEnd({ view }: { view: LedgerView }) {
  const today = useTehranDay();
  const asking = useMemo(
    () => worthItCandidates(view.entries, new Set(worthItAnswers(rows('decisions')).keys()), today, largeSpendThreshold(view.entries)),
    [view, today],
  );
  if (!asking.length) return <DeckDone view={view} today={today} />;
  return (
    <div class="screen no-tabs">
      <div class="screen-head">
        <ScreenTitle>مرور هفتگی</ScreenTitle>
        <PillButton label="بستن" onClick={closePage} />
      </div>
      <div class="gap-m">
        {asking.map((candidate) => (
          <WorthItCard key={candidate.txn.ref} entry={candidate} onAnswer={(answer: string) => answerWorthIt(candidate, answer)} />
        ))}
        <div><PillButton label="بعداً" onClick={closePage} /></div>
      </div>
    </div>
  );
}

/**
 * Calm on purpose: no confetti and no score. What it earns is the figure the app is judged on —
 * how much of the month never needed her — and only when the month has enough rows for it to mean
 * anything.
 */
function DeckDone({ view, today }: { view: LedgerView; today: number }) {
  const month = useMemo(() => currentMonthReport(view.entries, today), [view, today]);
  const share = month.transactions >= 5 ? month.automaticShare : null;
  return (
    <div class="screen no-tabs deck-done">
      <div class="deck-done-body">
        <span class="disc done-disc"><CheckMark size={46} color="var(--tertiary)" /></span>
        <h1 class="done-title">همه‌چی بررسی شد</h1>
        <p class="muted done-caption">هر رقمی که توی گزارش‌هاست، حالا تأییدشده‌ست.</p>
        {share != null && (
          <div class="done-share">
            <span class="figure">{`${faNumber(Math.round(share * 100))}٪`}</span>
            <p class="muted">{`از ${faNumber(month.transactions)} تراکنش این ماه بدون پرسیدن ازت دسته‌بندی شد`}</p>
          </div>
        )}
      </div>
      <button type="button" class="pill primary wide done-back" onClick={closePage}>برگشت به دفتر</button>
    </div>
  );
}

/**
 * The plan spelled out before anything is written. Nothing here is a rule — every filing is an
 * ordinary answer she can undo one row at a time, and the sheet says so, which is what makes one
 * tap over fifty transactions a relief instead of a risk.
 */
function AutoFileSheet({ skipped = [] }: { skipped?: string[] }) {
  const view = useLedger();
  const plan = autoFilePlan(view.review.filter((e) => !skipped.includes(e.txn.ref)));
  const line = (count: number, what: string) => count > 0 && (
    <div class="autofile-line"><span class="figure">{faNumber(count)}</span><span>{what}</span></div>
  );
  return (
    <Sheet label="دسته‌بندی خودکار">
      <SheetTitle>دسته‌بندی خودکار</SheetTitle>
      <p class="muted" style={{ paddingTop: 'var(--s)', fontSize: '14px' }}>{`${faNumber(plan.total)} تراکنش منتظر، در یک حرکت:`}</p>
      <div class="autofile-lines">
        {line(plan.suggested, 'با همون پیشنهاد خود برنامه تأیید می‌شن')}
        {line(plan.shopping, 'برداشتِ بی‌نشونه می‌رن توی «خرید روزانه»')}
        {line(plan.other, 'مورد نامشخص می‌رن توی «سایر»')}
      </div>
      <p class="muted small" style={{ marginTop: 'var(--m)', lineHeight: '21px' }}>
        هیچ قانونی ساخته نمی‌شه — هر کدوم رو بعداً می‌تونی از دفتر باز کنی و عوض کنی.
      </p>
      <div class="sheet-actions">
        <button type="button" class="deck-answer primary" onClick={() => { categoriseAll(plan.assignments); closeSheet(); }}>دسته‌بندی کن</button>
        <button type="button" class="deck-answer quiet" onClick={closeSheet}>انصراف</button>
      </div>
    </Sheet>
  );
}

registerTab('LEDGER', { Component: TimelineScreen, badge: () => ledger().review.length });
registerPage('txn', TransactionPage);
registerPage('deck', ReviewDeck);
registerSheet('categoryFilter', CategoryFilterSheet);
registerSheet('autoFile', AutoFileSheet);
