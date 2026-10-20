/**
 * خانه and دارایی: one list, switched by `portfolio` (Ui.kt 906-1190), with Home.kt's story under
 * the total on خانه and the holdings banded by kind on دارایی.
 *
 * On خانه: her, above the money; the answer as the one deep-green object on the page; the verbs
 * she might reach for next, right under the number that prompts them; then how the month is going
 * and its newest rows, and last the total against the dollar and what it is made of.
 * On دارایی: the title line with its add pill, the compact hero, then what she owns. The sheets
 * behind the list are in homeSheets.tsx.
 *
 * Not ported: pull-to-refresh (the browser's own gesture reloads the page; the freshness line under
 * the total is the labelled control either way), the APK update card, and the stocks refresh the
 * picker kicks.
 */
import type { ComponentChildren } from 'preact';
import { useMemo } from 'preact/hooks';
import { ActIcon, CategoryIcon, categoryHue, customGlyphs, glyphOf } from './categoryIcon';
import type { CategoryGlyph } from './categoryIcon';
import { BANK_ID, KIND_FA, compositionByKind, holdingsByKind, resolveType, valuedInToman } from './catalog';
import type { AssetType, Kind } from './catalog';
import { changeOver, computeTotals, backupReminderDue, currentEffective, currentList, dollarLens, heldTomanCostUsd, nameOr, refreshAll, useWealthStatus } from './data';
import type { Change, DollarLens, Totals } from './data';
import { useLedger } from './derived';
import type { LedgerView } from './derived';
import { familyAssetViews, setFamilyTotal, useFamily } from './family';
import type { FamilyAssetView } from './family';
import { bidi, faAgo, faCompact, faDay, faDecimal, faHeld, faNumber, faRate, faUsd, faWordsToman, tomanOf, usdOf, moneyUnit, shownOf } from './format';
import { Chevron, PersonMark, PlusMark, TabIcon, TrendCaret } from './icons';
import type { TabId } from './icons';
import { DAY_MS, tehranDay } from './jalali';
import { holdingKey } from './model';
import type { Coin, Holding, LedgerEntry, WalletLink } from './model';
import { openPage, openReport, openSheet, registerTab, selectTab } from './nav';
import { readPlans } from './plans';
import { BudgetLevel, budgetNoteFa } from './budget';
import type { BudgetProgress } from './budget';
import { chequeWhenFa, dueSoon } from './cheques';
import type { DueItem } from './cheques';
import { BankLogo } from './logos';
import { buildStory } from './reports';
import type { HomeStory } from './reports';
import { BAR_TINT, CategoryShareRow, InsightCard, QuietStart, roundEven } from './report';
import { pref, useData } from './state';
import { ActionCircle, HeroPanel, PillToggle, ScreenTitle, Section, TitleAddButton } from './ui';
import { AssetIcon, RowAmount, RowTitle, rateIn, useAutoSize, useNow } from './homeSheets';
import { LoansHeroStrip } from './loansUi';
import { TimelineRow, openTxn, useTehranDay } from './timeline';
import './home.css';

// ---- the list -----------------------------------------------------------------------------------

function HomeList({ portfolio }: { portfolio: boolean }) {
  useData();
  useFamily();
  const status = useWealthStatus();
  const view = useLedger();
  const list = currentList();
  const effective = currentEffective();
  const totals = computeTotals(list, effective);
  const coins = pref('rates')?.coins ?? [];
  const familyAssets = familyAssetViews();

  return (
    <div class="screen home">
      {portfolio ? (
        // دارایی is a room like دفتر and آینده, so it opens the way they do: its name, and the one
        // thing it writes beside it — دفتر's title line exactly, so the title stays put between tabs.
        <div class="title-line">
          <ScreenTitle>دارایی</ScreenTitle>
          <TitleAddButton word="دارایی" label="اضافه کردن دارایی" onClick={() => openSheet('pickType')} />
        </div>
      ) : (
        // Her, above the money: the greeting is the door to تنظیمات. خانه writes one thing, where every
        // room does — the add pill at the end of its top line: cash paid at the till belongs to no tab.
        <div class="home-top-line">
          <HomeTopBar name={pref('name')} />
          <TitleAddButton word="تراکنش" label="تراکنش دستی" onClick={() => openSheet('manualTxn')} />
        </div>
      )}

      {backupReminderDue(pref('backupReminderEnabled'), pref('lastBackupAt'), Date.now()) && (
        // A door, not a caption: it leads to تنظیمات, so it wears the card and chevron every door does.
        <button type="button" class="backup-due" onClick={() => openPage('settings', { room: 'BACKUP' })}>
          <span>وقت پشتیبان جدیده. از تنظیمات یک فایل پشتیبان بساز.</span>
          <Chevron size={24} />
        </button>
      )}

      <HeroCard totals={totals} usdRate={rateIn(effective, 'usd')} portfolio={portfolio} familyAssets={familyAssets} />

      {/* خانه is a reading; its one verb is the greeting row's pill and the asset is دارایی's to add. The
          circle stays only before she holds anything, where adding the first asset is the one thing the
          page can ask for — named in full, since «اضافه کردن» beside «+ تراکنش» would not say what. */}
      {!portfolio && list.length === 0 && (
        <div class="action-circles">
          <ActionCircle label="اضافه کردن دارایی" icon={<PlusMark size={24} />} onClick={() => openSheet('pickType')} />
        </div>
      )}

      {!portfolio ? (
        <>
          <TabSections view={view} total={totals.toman} effective={effective} list={list} coins={coins} />
          {totals.missing.length > 0 && <MissingNote missing={totals.missing} coins={coins} />}
        </>
      ) : (
        <>
          {list.length === 0 ? <EmptyHint /> : (
            <Holdings list={list} effective={effective} coins={coins} view={view}
              refreshing={status.refreshingWallets} errors={status.walletErrors} />
          )}
          {totals.missing.length > 0 && <MissingNote missing={totals.missing} coins={coins} />}
          {/* What the family shares back, under her own: one band per member, as sent. */}
          {familyAssets.map((shared) => (
            <div key={shared.memberId}>
              <Section title={`دارایی ${shared.name}`}
                trailing={shared.items.length > 1 ? <span class="sub figure">{`${faCompact(shared.totalToman, 3, true)} ${moneyUnit()}`}</span> : undefined} />
              <FamilyAssetBand shared={shared} />
            </div>
          ))}
        </>
      )}
    </div>
  );
}

/**
 * Her, and the door to تنظیمات: the disc, the name and the chevron are one target. Without a name
 * it reads «تنظیمات», because on a first run the app's own name tells her nothing.
 */
function HomeTopBar({ name }: { name: string }) {
  const her = name.trim();
  return (
    <button type="button" class="home-top" onClick={() => openPage('settings')} aria-label={her ? `${her}، تنظیمات` : 'تنظیمات'}>
      <span class="disc"><PersonMark size={24} /></span>
      <span class="name">{her ? `سلام، ${her}` : 'تنظیمات'}</span>
      <Chevron size={18} />
    </button>
  );
}

// ---- the hero -----------------------------------------------------------------------------------

function HeroCard({ totals, usdRate, portfolio, familyAssets }: {
  totals: Totals; usdRate: number | null; portfolio: boolean; familyAssets: FamilyAssetView[];
}) {
  // The household total is a reading of the card, never a second total: history, the report and
  // the snapshot stay hers.
  const familyMode = pref('familyTotal') && familyAssets.length > 0;
  const total = totals.toman + (familyMode ? familyAssets.reduce((sum, a) => sum + a.totalToman, 0) : 0);
  const usd = usdOf(total, usdRate);
  const history = pref('history');
  // A month, against her own total only — the history never recorded the household's.
  const change = useMemo(() => changeOver(history, Math.trunc(Date.now() / DAY_MS), 30, totals.toman), [history, totals.toman]);
  const shownChange = familyMode ? null : change;
  const figure = faCompact(total, 3, true);
  const words = faWordsToman(total);
  const usdAside = usd != null && <span class="usd figure" aria-label={`${faRate(usd)} دلار`}>{faUsd(usd)}</span>;

  // دارایی's header, not خانه's answer (Ui.kt HeroCard `compact`): whose, how much, how fresh, in a
  // third of the height, so the list the tab exists for starts above the fold. The words, the exact
  // digits, the loans and the change pill stay on خانه's card; no «جمع دارایی‌هات» either — under
  // the دارایی tab, above its own breakdown, the figure names itself.
  if (portfolio) {
    return (
      <div class="home-hero compact">
        <HeroPanel>
          {familyAssets.length > 0 && <HeroScopeToggle family={familyMode} onSelect={setFamilyTotal} />}
          {/* The figure has its line to itself and the dollars sit under it, as the exact digits do on
              خانه's card: beside it they took the width the figure shrinks into, and sat on the
              headline's line as a second headline. */}
          <HeroFigure key={figure} figure={figure} max={34} min={20} />
          {usdAside && <p class="usd-line">{usdAside}</p>}
          <FreshnessLine />
        </HeroPanel>
      </div>
    );
  }

  return (
    <div class="home-hero">
      <HeroPanel>
        <div class="label-row">
          {familyAssets.length === 0
            ? <span class="label">جمع دارایی‌هات</span>
            : <HeroScopeToggle family={familyMode} onSelect={setFamilyTotal} />}
          {usdAside}
        </div>
        {/* Keyed on the figure: every refresh lifts the new one into place, the receipt that it did something. */}
        <HeroFigure key={figure} figure={figure} />
        {/* Digits are quick to scan but easy to misread by a factor of ten; the words are the check. */}
        {words && <p class="words">{words}</p>}
        <p class="full figure">{`${faNumber(shownOf(total))} ${moneyUnit()}`}</p>
        {shownChange ? <ChangePill change={shownChange} onClick={() => openReport('ASSETS')} />
          // Before thirty days there is no honest figure; the slot keeps its target and drops the number.
          : <ReportLink onClick={() => openReport('ASSETS')} />}
        {/* Beside the total, never in it: after its own story, the card's second door. Hers alone, so
            not on the household's reading. */}
        {!familyMode && <LoansHeroStrip />}
        <FreshnessLine />
      </HeroPanel>
    </div>
  );
}

/**
 * How old the rates under the total are, and the way to make them younger (Ui.kt FreshnessLine):
 * the sentence that tells her whether she needs a refresh is the thing she presses, instead of a
 * third circle in the action row. Same reach as Android's: rates and wallet balances.
 */
export function FreshnessLine() {
  const { error, refreshing: busy } = useWealthStatus();
  const now = useNow();
  const updatedAt = pref('rates')?.updatedAt ?? 0;
  // A day-old rate silently shown as current is the quietly wrong total this app exists to avoid.
  const stale = updatedAt > 0 && now - updatedAt > 24 * 60 * 60_000;
  const failed = error != null && updatedAt === 0;
  // Anything worth a caution sentence dims the dot too: an all-clear green beside «متصل نشد» contradicts the words.
  const trouble = failed || stale || error != null;
  // Work in flight says so in words — the one case DESIGN.md lets a control dim for.
  const line = busy ? 'در حال تازه کردن…'
    : failed ? 'نرخ‌ها به‌روز نشدن. اینترنتت رو چک کن'
      : stale ? `نرخ‌ها قدیمی‌ان؛ ${faAgo(updatedAt, now)}`
        : `نرخ‌ها: ${faAgo(updatedAt, now)}`;
  const caution = error != null && updatedAt > 0 ? 'متصل نشد. نرخ‌های قبلی نشون داده می‌شن.' : null;
  return (
    <button type="button" class="freshness" onClick={refreshAll} disabled={busy}>
      <span class="hero-strip">
        <span class={`dot${trouble ? ' trouble' : ''}`} />
        <span class={(stale || failed) && !busy ? 'warn' : undefined}>{line}</span>
        {/* Beside the words, not across the card: the mark is what turns a caption into a control. */}
        <ActIcon glyph="REFRESH" size={16} stroke={1.5} class={busy ? 'spin' : undefined} />
      </span>
      {caution && <span class="stale-note">{caution}</span>}
    </button>
  );
}

/**
 * Never wraps: «۳٫۲ میلیارد تومان» broken over two lines reads as a layout bug, so it shrinks to
 * fit between 28 and 60 instead (20 and 34 on دارایی's compact header). Three decimals here: the
 * headline is the one figure she watches move.
 */
function HeroFigure({ figure, max = 60, min = 28 }: { figure: string; max?: number; min?: number }) {
  const ref = useAutoSize<HTMLSpanElement>(max, min, figure);
  return <span ref={ref} class="hero-total figure fig">{figure}<span class="unit">{moneyUnit()}</span></span>;
}

/** Whose money the headline counts, in the label's own slot and the field's own colours. */
function HeroScopeToggle({ family, onSelect }: { family: boolean; onSelect: (family: boolean) => void }) {
  return <PillToggle options={[false, true]} selected={family} label={(o) => o ? 'خانواده' : 'دارایی‌هات'} onSelect={onSelect} />;
}

/** The way into the report when the change pill has nothing honest to say yet. */
function ReportLink({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" class="hero-door link" onClick={onClick}>
      گزارش دارایی<Chevron size={18} />
    </button>
  );
}

/** How the total has moved in a month. Percent leads: it survives being compared to last month's. */
function ChangePill({ change, onClick }: { change: Change; onClick: () => void }) {
  const flat = Math.abs(change.delta) < 1;
  const gained = change.delta > 0;
  const tone = flat ? 'var(--hero-muted)' : gained ? 'var(--hero-mint)' : 'var(--hero-warn)';
  const figure = flat ? 'بدون تغییر'
    : change.percent != null ? `${faDecimal(Math.abs(change.percent), 1)}٪` : faCompact(Math.abs(change.delta));
  const tail = flat ? 'در ۳۰ روز گذشته' : gained ? 'بیشتر از ۳۰ روز پیش' : 'کمتر از ۳۰ روز پیش';
  return (
    <button type="button" class="hero-door" onClick={onClick}>
      <span class="pillbox figure" style={{ '--tone': tone }}>
        {!flat && <TrendCaret up={gained} color={tone} />}
        {figure}
      </span>
      {tail}
      {/* The chevron is what makes a percentage on a green field a door rather than a statistic. */}
      <Chevron size={18} />
    </button>
  );
}

// ---- خانه: the story ----------------------------------------------------------------------------

/**
 * The month as its own card, then one good line and at most one thing asking for her — and then it
 * stops. Every figure comes out of buildStory, so what she reads and what the tests check agree.
 */
/**
 * خانه as the app's index (Ui.kt's home list): under the total, one section per tab in the bar's own
 * order, each headed by that tab's mark and name — the head is the door, what sits under it is the
 * tab at a glance. A tab with nothing to say has no section, except گزارش, whose quiet start is the
 * thing to do on an empty ledger.
 */
function TabSections({ view, total, effective, list, coins }: {
  view: LedgerView; total: number; effective: Record<string, number>; list: Holding[]; coins: Coin[];
}) {
  const reportExcluded = pref('reportExcluded');
  const installmentDays = pref('installmentReminder');
  const plans = useMemo(() => readPlans(view.allEntries, view.entries), [view]);
  const story = useMemo<HomeStory>(() => buildStory(view.entries, view.bankTotalRial, tehranDay(Date.now()), {
    budgets: plans.budgets,
    // The one gate دخل و خرج reads: قرض و همسر are its shipped default, not a mechanism of their own.
    countPassThrough: true,
    excluded: new Set(reportExcluded),
    mineId: view.mineId,
    installments: plans.installments,
    installmentDays,
    cheques: plans.cheques,
    accounts: view.bankAccounts,
  }), [view, plans, reportExcluded, installmentDays]);
  // آینده's next two weeks — or the reminder's own window, where she has set a longer one.
  const due = useMemo(() => dueSoon(plans.cheques, plans.installments, view.bankAccounts, tehranDay(Date.now()),
    installmentDays, Math.max(14, installmentDays)), [plans, view, installmentDays]);
  const recent = recentEntries(view.entries);
  const waiting = view.review.length;
  const history = pref('history');
  const rateHistory = pref('rateHistory');
  const usd = rateIn(effective, 'usd');
  const lens = useMemo(() => dollarLens(history, rateHistory, Math.trunc(Date.now() / DAY_MS), total, usd),
    [history, rateHistory, total, usd]);
  const composition = compositionByKind(list, coins, effective);
  return (
    <>
      {/* دفتر: the newest rows, and the review queue on the head's line — دفتر's own pill, its own deck. */}
      {recent.length > 0 && (
        <section class="tab-section">
          <TabSectionHead tab="LEDGER" onOpen={() => selectTab('LEDGER')}>
            {waiting > 0 && <button type="button" class="pill primary ledger-pill" onClick={() => openPage('deck')}>{`مرور ${faNumber(waiting)} مورد`}</button>}
          </TabSectionHead>
          <RecentActivity entries={recent} />
        </section>
      )}
      {/* آینده: what falls due soon, with the cheque card's own warnings, and the caps furthest along. */}
      {(due.length > 0 || plans.budgets.length > 0) && (
        <section class="tab-section">
          <TabSectionHead tab="BUDGET" onOpen={() => selectTab('BUDGET')} />
          <FutureSummary due={due} budgets={plans.budgets} custom={customGlyphs(view.categories)} />
        </section>
      )}
      {/* دارایی: the total against the dollar, and what it is made of — with what its Toman share cost. */}
      {(lens || composition.length > 1) && (
        <section class="tab-section">
          <TabSectionHead tab="ASSETS" onOpen={() => selectTab('ASSETS')} />
          {lens && <DollarLensCard lens={lens} />}
          {composition.length > 1 && <WhereMoneyIs composition={composition} lens={lens} usdNow={usd} />}
        </section>
      )}
      {/* گزارش: the month, and the one sentence worth leading with — its «why» left to the report. */}
      <section class="tab-section">
        <TabSectionHead tab="REPORT" onOpen={() => openReport('CASH_FLOW')} />
        {story.month.transactions > 0 ? <MonthFlow story={story} onOpen={() => openReport('CASH_FLOW')} /> : <QuietStart />}
        {story.headline && <InsightCard insight={story.headline} />}
      </section>
    </>
  );
}

/**
 * A section's head (Home.kt TabSectionHead): the tab's own mark from the bar, its name and the chevron,
 * one door to the tab; a size over the cards' titles beneath it. [children] is that tab's one act.
 */
function TabSectionHead({ tab, onOpen, children }: { tab: TabId; onOpen: () => void; children?: ComponentChildren }) {
  return (
    <div class="tab-section-head">
      <button type="button" class="tab-door" onClick={onOpen} aria-label={`${TAB_FA[tab]} رو باز کن`}>
        <TabIcon tab={tab} />
        <h2>{TAB_FA[tab]}</h2>
        <Chevron size={18} />
      </button>
      {children}
    </div>
  );
}

const TAB_FA: Record<TabId, string> = { HOME: 'خانه', LEDGER: 'دفتر', BUDGET: 'آینده', ASSETS: 'دارایی', REPORT: 'گزارش' };

/** How many of آینده's dues and caps خانه lists — the soonest and the furthest along. */
const FUTURE_ROWS = 4;
const FUTURE_CAPS = 3;

/** آینده at a glance (Home.kt FutureSummary): what falls due soon, then the caps furthest along. */
function FutureSummary({ due, budgets, custom }: { due: DueItem[]; budgets: BudgetProgress[]; custom: Record<string, CategoryGlyph> }) {
  const today = useTehranDay();
  const caps = [...budgets].sort((a, b) => b.percent - a.percent).slice(0, FUTURE_CAPS);
  return (
    <div class="future">
      {due.slice(0, FUTURE_ROWS).map((item) => <DueRow key={`${item.title}:${item.day}`} item={item} today={today} />)}
      {caps.length > 0 && due.length > 0 && <span class="future-gap" />}
      {caps.map((b) => <BudgetLine key={b.goal.id} budget={b} custom={custom} />)}
    </div>
  );
}

/**
 * One due (Home.kt DueRow): a warning takes the line under the name in ink, in the cheque card's own
 * words, and the day moves beside the figure in caution amber; bold `error` once the day has passed.
 */
function DueRow({ item, today }: { item: DueItem; today: number }) {
  const late = item.day < today;
  const when = chequeWhenFa(item.day, today);
  return (
    <button type="button" class="due-row" onClick={() => selectTab('BUDGET')}>
      {item.bank ? <BankLogo bank={item.bank} size={36} /> : <span class="due-mark"><CategoryIcon glyph="INSTALMENT" size={20} /></span>}
      <span class="grow due-text">
        <span class="due-title ellipsis">{item.title}</span>
        <span class={`due-line${item.warning ? ' warned' : late ? ' late' : ''}`}>{item.warning ?? when}</span>
      </span>
      <span class="due-end">
        <span class="figure due-amount">{bidi(faCompact(tomanOf(item.rial)))}</span>
        {item.warning && <span class={`due-when${late ? ' late' : ''}`}>{when}</span>}
      </span>
    </button>
  );
}

/**
 * One cap (Home.kt BudgetLine), with the budget card's own sentence under its bar — the words that
 * say why it is amber — so colour only confirms: the selection colour on pace, caution amber ahead of
 * its days or near the cap, `error` once over.
 */
function BudgetLine({ budget, custom }: { budget: BudgetProgress; custom: Record<string, CategoryGlyph> }) {
  const over = budget.overRial > 0;
  const tone = over ? 'var(--error)' : budget.level >= BudgetLevel.NEAR || budget.outpacing ? 'var(--secondary)' : 'var(--primary)';
  const hue = categoryHue(budget.categoryFa, custom);
  const said = over ? 'از سقف گذشتی' : `${faNumber(budget.percent)}٪`;
  const note = budgetNoteFa(budget);
  return (
    <button type="button" class="budget-line" onClick={() => selectTab('BUDGET')}
      aria-label={`${budget.categoryFa}: ${over ? 'از سقف گذشتی' : `${faNumber(budget.percent)} درصد از سقف رفته`}${note ? `. ${note}` : ''}`}>
      <span class="budget-line-row">
        <span class="disc" style={{ width: '32px', height: '32px', background: `color-mix(in srgb, ${hue} 16%, transparent)` }}>
          <CategoryIcon glyph={glyphOf(budget.categoryFa, custom)} size={17} stroke={1.5} color={hue} />
        </span>
        <span class="grow ellipsis">{budget.categoryFa}</span>
        <span class="figure budget-said" style={over ? { color: tone } : undefined}>{said}</span>
      </span>
      <span class="rp-track"><span style={{ width: `${Math.min(1, Math.max(0.02, budget.share)) * 100}%`, background: tone }} /></span>
      {note && <span class="budget-note">{note}</span>}
    </button>
  );
}

/** What came in against what went out, as one object: the bar is the comparison. */
export function MonthFlow({ story, onOpen }: { story: HomeStory; onOpen: () => void }) {
  const month = story.month;
  const income = tomanOf(month.incomeRial);
  const spent = tomanOf(month.spentRial);
  // Where the خرج went, three deep: the report's own category rows, the long list one tap away.
  const top = month.spentRial > 0 ? month.spendingByCategory.slice(0, 3) : [];
  return (
    <HomeCard title="این ماه" class="month-flow" onOpen={onOpen}
      label={`این ماه: درآمد ${faCompact(income)} ${moneyUnit()}، خرج ${faCompact(spent)} ${moneyUnit()}`
        + top.map(([name, rial]) => `، ${name} ${faCompact(tomanOf(rial))} ${moneyUnit()}`).join('')
        + '. برای گزارش کامل باز کن'}>
      <span class="halves">
        <FlowHalf label="درآمد" rial={month.incomeRial} tone="var(--tertiary)" />
        <FlowHalf label="خرج" rial={month.spentRial} tone="var(--on-surface)" />
      </span>
      {(month.incomeRial > 0 || month.spentRial > 0) && <FlowBar incomeRial={month.incomeRial} spentRial={month.spentRial} />}
      {top.length > 0 && (
        <span class="top-spend">
          <span class="caption">بیشترین خرج‌ها</span>
          {top.map(([name, rial]) => <CategoryShareRow key={name} name={name} rial={rial} total={month.spentRial} />)}
        </span>
      )}
    </HomeCard>
  );
}

/**
 * The make every card on خانه shares (Home.kt HomeCard): a 15px title with the chevron that says
 * the card is a door, and one spoken sentence for the whole of it.
 */
function HomeCard({ title, label, onOpen, class: cls = '', children }: {
  title: string; label: string; onOpen: () => void; class?: string; children: ComponentChildren;
}) {
  return (
    <button type="button" class={`home-card ${cls}`} onClick={onOpen} aria-label={label}>
      <span class="head"><span>{title}</span><Chevron size={18} /></span>
      {children}
    </button>
  );
}

/** A card's answer before its evidence: the figure at FlowHalf's size, its sentence a step down. */
function LeadFigure({ figure, tone, tail, up }: { figure: string; tone: string; tail: string; up?: boolean }) {
  return (
    <span class="lead">
      <span class="lead-figure figure" style={{ color: tone }}>
        {up != null && <TrendCaret up={up} color={tone} size={10} />}
        {figure}
      </span>
      <span class="lead-tail">{tail}</span>
    </span>
  );
}

/** The shortest a segment may be drawn while still reading as a bar rather than a dot. */
const FLOW_FLOOR = 0.06;

/** Two lengths off one baseline. The shares are floored: both exact figures are stated just above. */
function FlowBar({ incomeRial, spentRial }: { incomeRial: number; spentRial: number }) {
  const share = incomeRial <= 0 ? 0 : spentRial <= 0 ? 1
    : Math.min(1 - FLOW_FLOOR, Math.max(FLOW_FLOOR, incomeRial / (incomeRial + spentRial)));
  return (
    <span class="flow-bar" aria-hidden="true">
      {incomeRial > 0 && <span style={{ flex: share, background: 'var(--tertiary)' }} />}
      {spentRial > 0 && <span style={{ flex: 1 - share, background: 'var(--on-surface-variant)' }} />}
    </span>
  );
}

function FlowHalf({ label, rial, tone }: { label: string; rial: number; tone: string }) {
  const text = bidi(faCompact(tomanOf(rial)));
  const ref = useAutoSize<HTMLDivElement>(26, 16, text);
  return (
    <span class="half">
      <span>{label}</span>
      <div ref={ref} class="figure" style={{ color: tone }}>{text}</div>
    </span>
  );
}

function MissingNote({ missing, coins }: { missing: string[]; coins: Coin[] }) {
  const names = [...new Set(missing.map((id) => resolveType(id, coins).fa))].join('، ');
  return <p class="missing-note">{`نرخ ${names} پیدا نشد و توی جمع حساب نشده. می‌تونی نرخش رو دستی وارد کنی.`}</p>;
}

// ---- دارایی: the bands --------------------------------------------------------------------------

function Holdings({ list, effective, coins, view, refreshing, errors }: {
  list: Holding[]; effective: Record<string, number>; coins: Coin[]; view: LedgerView;
  refreshing: ReadonlySet<string>; errors: ReadonlyMap<string, string>;
}) {
  // Banding by kind separates without moving anything: sections come out in stored order.
  const sections = [...holdingsByKind(list, coins)];
  return (
    <>
      {sections.map(([kind, held]) => {
        // The same function as the hero, so sections always add up to it; counted contributors, not
        // rows — a sum of one is the card below it said twice.
        const sum = computeTotals(held, effective);
        const counted = held.filter((h) => !h.excluded).length - sum.missing.length;
        return (
          <div key={kind}>
            {sections.length > 1
              ? <Section title={KIND_FA[kind]} trailing={counted > 1 ? <span class="sub figure">{`${faCompact(sum.toman, 3, true)} ${moneyUnit()}`}</span> : undefined} />
              : <div class="band-gap" />}
            <div class="band holdings">
              {held.map((h) => {
                const type = resolveType(h.typeId, coins);
                const bank = h.typeId === BANK_ID;
                return (
                  <HoldingRow key={holdingKey(h)} type={type} name={nameOr(h, type.fa)} amount={h.amount}
                    rate={rateIn(effective, h.typeId)} excluded={h.excluded}
                    // The bank row is not hers to edit — its amount comes from the messages.
                    onClick={() => (bank ? openSheet('bank') : openSheet('editHolding', { typeId: h.typeId, holdingKey: holdingKey(h) }))}
                    note={bank ? bankNote(view) : null} wallet={h.wallet}
                    walletRefreshing={refreshing.has(holdingKey(h))} walletError={errors.get(holdingKey(h)) ?? null} />
                );
              })}
            </div>
          </div>
        );
      })}
    </>
  );
}

/**
 * The bank row's second line: how many accounts are behind the figure, and when any is still a
 * guess, that too — a number she has to open something to distrust is a number she will trust.
 */
function bankNote(view: LedgerView): string {
  const live = view.bankAccounts.filter((a) => !a.disabled);
  const off = view.bankAccounts.length - live.length;
  const counted = `${faNumber(live.length)} حساب`;
  if (live.some((a) => !a.trusted)) return `${counted}  •  نیاز به بررسی`;
  if (off > 0) return `${counted}  •  ${faNumber(off)} خاموش`;
  return counted;
}

const shortWalletAddress = (address: string): string =>
  address.length <= 15 ? address : `${address.slice(0, 7)}...${address.slice(-5)}`;

function HoldingRow({ type, name, amount, rate, excluded, onClick, note, wallet, walletRefreshing, walletError }: {
  type: AssetType; name: string; amount: number; rate: number | null; excluded: boolean; onClick: () => void;
  note: string | null; wallet: WalletLink | null; walletRefreshing: boolean; walletError: string | null;
}) {
  const valued = valuedInToman(type);
  const held = `${faHeld(amount, type.dec)} ${bidi(type.unitFa)}`;
  // Null when this line is a sentence rather than an amount-and-rate pair.
  const shownRate = rate != null && note == null && !valued ? faRate(shownOf(rate)) : null;
  const visibleStatus = wallet && (walletRefreshing ? `در حال گرفتن از ${wallet.networkFa}`
    : walletError != null ? 'به‌روز نشد  •  موجودی قبلی نشون داده می‌شه'
      : `${wallet.networkFa}  •  ${bidi(shortWalletAddress(wallet.address))}`);
  const spokenStatus = wallet && (walletRefreshing ? `در حال گرفتن موجودی از ${wallet.networkFa}`
    : walletError != null ? 'موجودی به‌روز نشد؛ موجودی قبلی نشون داده می‌شه'
      : `خوندن خودکار از ${wallet.networkFa}، آدرس ${wallet.address}`);
  return (
    <button type="button" class="band-row" onClick={onClick}>
      <span class="holding">
        {/* Set aside dims the badge, and only by colour elsewhere: alpha took the caption below every floor. */}
        <span class={excluded ? 'dim' : ''}><AssetIcon type={type} network={wallet?.network} /></span>
        <span class="body">
          <span class="top">
            <span class="grow"><RowTitle text={name} max={17} class={excluded ? 'off' : ''} /></span>
            <span class="ends">
              {excluded ? (
                <>
                  {rate != null && <RowAmount toman={amount * rate} struck class="off" />}
                  <span class="state">توی جمع حساب نشده</span>
                </>
              ) : rate == null ? <span class="missing">نرخش پیدا نشد</span>
                : <RowAmount toman={amount * rate} unit={moneyUnit()} />}
            </span>
          </span>
          {shownRate == null ? (
            <span class="sub"><span class="held">{note ?? (valued ? 'دستی وارد شده' : held)}</span></span>
          ) : (
            // The rate is measured first: when the line cannot hold both, her amount gives way.
            <span class="sub">
              <span class="held">{held}</span>
              <span class="rate">•  نرخ <b>{shownRate}</b></span>
            </span>
          )}
          {wallet && <span class={`wallet${walletError != null ? ' error' : ''}`} aria-label={spokenStatus ?? undefined}>{visibleStatus}</span>}
        </span>
      </span>
    </button>
  );
}

/** One band per member, rows inside; nothing tappable — the figures are the owner's, as sent. */
function FamilyAssetBand({ shared }: { shared: FamilyAssetView }) {
  return (
    <div class="band holdings family">
      {shared.items.length === 0 && <p class="band-row muted" style={{ fontSize: 'calc(13px * var(--text))' }}>فعلاً چیزی برای نمایش نفرستاده.</p>}
      {shared.items.map((item, i) => (
        <div class="band-row" key={i}>
          <span class="grow"><RowTitle text={item.name} /></span>
          <RowAmount toman={item.toman} unit={moneyUnit()} />
        </div>
      ))}
    </div>
  );
}

/** It names the button rather than repeating it: two identical pills read as a mistake. */
function EmptyHint() {
  return (
    <div class="empty-hint">
      <span class="disc"><TabIcon tab="ASSETS" /></span>
      <h2>هنوز چیزی اضافه نکردی</h2>
      <p>پول نقد، دلار، طلا، سکه یا رمزارز رو از دکمه «+ دارایی» بالای صفحه اضافه کن تا جمعشون رو ببینی.</p>
    </div>
  );
}

// Stable components, so the shell keeps each tab mounted with its own scroll.
const HomeTab = () => <HomeList portfolio={false} />;
const AssetsTab = () => <HomeList portfolio />;
registerTab('HOME', { Component: HomeTab });
registerTab('ASSETS', { Component: AssetsTab });

// ---- خانه: the total's own story ------------------------------------------------------------------

/**
 * Whether she is any richer, or the dollar simply went up (Home.kt DollarLensCard): the answer
 * first, then the two moves on one scale — hers in the spending-share colour, the dollar's muted,
 * because the dollar is the yardstick and not a second account.
 */
function DollarLensCard({ lens }: { lens: DollarLens }) {
  const flat = Math.abs(lens.real) < 0.05;
  const tone = flat ? 'var(--on-surface-variant)' : lens.real > 0 ? 'var(--tertiary)' : 'var(--error)';
  const figure = flat ? 'بدون تغییر' : `${faDecimal(Math.abs(lens.real), 1)}٪`;
  const tail = flat ? 'در ۳۰ روز گذشته' : lens.real > 0 ? 'بیشتر از ۳۰ روز پیش' : 'کمتر از ۳۰ روز پیش';
  const top = Math.max(Math.abs(lens.wealth), Math.abs(lens.dollar));
  return (
    <HomeCard title="به دلار" onOpen={() => openReport('ASSETS')}
      label={`به دلار: ${figure} ${tail}. دارایی‌ات ${lensWords(lens.wealth)}، نرخ دلار ${lensWords(lens.dollar)}. برای گزارش دارایی باز کن`}>
      <LeadFigure figure={figure} tone={tone} tail={tail} up={flat ? undefined : lens.real > 0} />
      <span class="lens-bars">
        <LensBar label="دارایی‌ات" percent={lens.wealth} top={top} tint="var(--primary)" />
        <LensBar label="نرخ دلار" percent={lens.dollar} top={top} tint="var(--on-surface-variant)" />
      </span>
    </HomeCard>
  );
}

const lensWords = (percent: number): string =>
  Math.abs(percent) < 0.05 ? 'بدون تغییر'
    : percent > 0 ? `${faDecimal(percent, 1)} درصد بیشتر` : `${faDecimal(-percent, 1)} درصد کمتر`;

/** The shortest a lens bar is drawn while it still reads as a move rather than as none. */
const LENS_FLOOR = 0.04;

function LensBar({ label, percent, top, tint }: { label: string; percent: number; top: number; tint: string }) {
  const flat = Math.abs(percent) < 0.05;
  const width = flat || top <= 0 ? 0 : Math.min(1, Math.max(LENS_FLOOR, Math.abs(percent) / top));
  return (
    <span class="lens-bar">
      <span class="lens-label">{label}</span>
      <span class="lens-track">{width > 0 && <span style={{ width: `${width * 100}%`, background: tint }} />}</span>
      <span class="lens-pct figure">
        {!flat && <TrendCaret up={percent > 0} color="var(--on-surface-variant)" size={8} />}
        {`${faDecimal(Math.abs(percent), 1)}٪`}
      </span>
    </span>
  );
}

/** A share as she would say it: whole percents, with a decimal only where the whole would be «۰». */
const sharePercent = (share: number): string =>
  share < 0.01 ? faDecimal(share * 100, 1) : faNumber(roundEven(share * 100));

/**
 * What the total is made of, largest first (Home.kt WhereMoneyIs), led by the share that matters
 * most in a rial economy: how much of it is still Toman.
 */
function WhereMoneyIs({ composition, lens, usdNow }: {
  composition: Array<[Kind, number]>; lens: DollarLens | null; usdNow: number | null;
}) {
  const total = composition.reduce((sum, [, v]) => sum + v, 0);
  if (total <= 0) return null;
  const parts = [...composition].sort((a, b) => b[1] - a[1]);
  const toman = composition.find(([kind]) => kind === 'CASH')?.[1];
  // The share's price: today's Toman at the dollar of the lens's first day. Under a twentieth of a
  // percent, or under a dollar, it is a sentence about nothing.
  const cost = toman != null && lens && usdNow != null && usdNow > 0 && Math.abs(lens.dollar) >= 0.05
    ? heldTomanCostUsd(lens, toman, usdNow) : null;
  const costWords = cost != null && Math.abs(cost) >= 1
    ? `با نرخ دلار ۳۰ روز پیش، همین پول تومنی ${bidi(faUsd(Math.abs(cost)))} ${cost > 0 ? 'بیشتر' : 'کمتر'} می‌ارزید.` : null;
  return (
    <HomeCard title="پولت کجاست" onOpen={() => openReport('ASSETS')}
      label={`پولت کجاست: ${parts.map(([kind, v]) => `${KIND_FA[kind]} ${sharePercent(v / total)} درصد`).join('، ')}. ${costWords ? `${costWords} ` : ''}برای گزارش دارایی باز کن`}>
      {toman != null && <LeadFigure figure={`${sharePercent(toman / total)}٪`} tone="var(--on-surface)" tail="از دارایی‌ات به تومانه" />}
      {toman != null && costWords && <span class="lead-note">{costWords}</span>}
      {/* Floored like the month's bar: a sliver under the gap would be a kind she holds drawn as none. */}
      <span class="where-bar">
        {parts.map(([kind, v]) => <span key={kind} style={{ flexGrow: Math.max(v / total, 0.015), background: BAR_TINT[kind] }} />)}
      </span>
      <span class="where-legend">
        {parts.map(([kind, v]) => (
          <span key={kind} class="where-kind">
            <span class="dot" style={{ background: BAR_TINT[kind] }} />
            <span class="name">{KIND_FA[kind]}</span>
            <span class="figure">{`${sharePercent(v / total)}٪`}</span>
          </span>
        ))}
      </span>
    </HomeCard>
  );
}

/** How many rows خانه shows of دفتر — enough to see the day moving, few enough to stay a glance. */
const RECENT_ROWS = 4;

/** The newest RECENT_ROWS of the ledger, as دفتر lists them: hidden duplicates left out. */
export const recentEntries = (entries: LedgerEntry[]): LedgerEntry[] =>
  entries.filter((e) => !e.duplicate).sort((a, b) => b.txn.at - a.txn.at).slice(0, RECENT_ROWS);

/**
 * The newest few rows of دفتر, on the paper (Home.kt RecentActivity); the section head above is the
 * door to the rest. Under a plain day name and never the ledger's day total: four rows are not a whole day.
 */
function RecentActivity({ entries }: { entries: LedgerEntry[] }) {
  const today = useTehranDay();
  if (!entries.length) return null;
  const days: Array<[number, LedgerEntry[]]> = [];
  for (const e of entries) {
    const last = days[days.length - 1];
    if (last && last[0] === e.txn.day) last[1].push(e);
    else days.push([e.txn.day, [e]]);
  }
  return (
    <div class="recent">
      {days.map(([day, rows]) => (
        <div key={day}>
          <h3 class="recent-day">{faDay(day, today)}</h3>
          {rows.map((e) => <TimelineRow key={`${e.txn.ref}:${e.categoryId}`} entry={e} onClick={() => openTxn(e.txn.ref)} />)}
        </div>
      ))}
    </div>
  );
}
