/**
 * خانه and دارایی: one list, switched by `portfolio` (Ui.kt 906-1190), with Home.kt's story under
 * the total on خانه and the holdings banded by kind on دارایی.
 *
 * Her, above the money; the answer as the one deep-green object on the page; the verbs she might
 * reach for next, right under the number that prompts them; then either how the month is going
 * (خانه) or what she owns (دارایی). The sheets behind the list are in homeSheets.tsx.
 *
 * Not ported: pull-to-refresh (the browser's own gesture reloads the page; «تازه کردن» is the
 * labelled control either way), the APK update card, and the stocks refresh the picker kicks.
 */
import { useMemo } from 'preact/hooks';
import { BANK_ID, KIND_FA, holdingsByKind, resolveType, valuedInToman } from './catalog';
import type { AssetType } from './catalog';
import { changeOver, computeTotals, backupReminderDue, currentEffective, currentList, nameOr, refreshAll, useWealthStatus } from './data';
import type { Change, Totals } from './data';
import { useLedger } from './derived';
import type { LedgerView } from './derived';
import { familyAssetViews, setFamilyTotal, useFamily } from './family';
import type { FamilyAssetView } from './family';
import { bidi, faAgo, faCompact, faDecimal, faHeld, faNumber, faRate, faWordsToman, tomanOf, usdOf } from './format';
import { Chevron, PersonMark, PlusMark, RefreshMark, TabIcon, TrendCaret } from './icons';
import { DAY_MS, tehranDay } from './jalali';
import { holdingKey } from './model';
import type { Coin, Holding, WalletLink } from './model';
import { openPage, openReport, openSheet, registerTab, selectTab } from './nav';
import { readPlans } from './plans';
import { buildStory } from './reports';
import type { HomeStory } from './reports';
import { InsightCard, QuietStart } from './report';
import { pref, useData } from './state';
import { ActionCircle, HeroPanel, Section } from './ui';
import { AssetIcon, RowAmount, RowTitle, rateIn, useAutoSize, useNow } from './homeSheets';
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
      {/* Her, above the money: the greeting is the door to تنظیمات. */}
      <HomeTopBar name={pref('name')} />

      {backupReminderDue(pref('backupReminderEnabled'), pref('lastBackupAt'), Date.now()) && (
        <button type="button" class="text-btn backup-due" onClick={() => openPage('settings', { room: 'BACKUP' })}>
          وقت پشتیبان جدیده. از تنظیمات یک فایل پشتیبان بساز.
        </button>
      )}

      <HeroCard totals={totals} usdRate={rateIn(effective, 'usd')} portfolio={portfolio} familyAssets={familyAssets} error={status.error} />

      {/* Real verbs only, fixed cells clustered to the centre so two circles never read as three with one missing. */}
      <div class="action-circles">
        <ActionCircle label="اضافه کردن" icon={<PlusMark size={24} />} onClick={() => openSheet('pickType')} />
        {/* Money no message will report, written down where she is standing. */}
        {!portfolio && <ActionCircle label="تراکنش دستی" icon={<TabIcon tab="LEDGER" />} onClick={() => openSheet('manualTxn')} />}
        <ActionCircle label="تازه کردن" quiet icon={<RefreshMark spinning={status.refreshing} />} disabled={status.refreshing} onClick={refreshAll} />
      </div>

      {!portfolio ? (
        <>
          <Story view={view} />
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
                trailing={shared.items.length > 1 ? <span class="sub figure">{`${faCompact(shared.totalToman, 3, true)} تومان`}</span> : undefined} />
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

function HeroCard({ totals, usdRate, portfolio, familyAssets, error }: {
  totals: Totals; usdRate: number | null; portfolio: boolean; familyAssets: FamilyAssetView[]; error: string | null;
}) {
  // The household total is a reading of the card, never a second total: history, the report and
  // the snapshot stay hers.
  const familyMode = pref('familyTotal') && familyAssets.length > 0;
  const total = totals.toman + (familyMode ? familyAssets.reduce((sum, a) => sum + a.totalToman, 0) : 0);
  const usd = usdOf(total, usdRate);
  const now = useNow();
  const updatedAt = pref('rates')?.updatedAt ?? 0;
  // A day-old rate silently shown as current is the quietly wrong total this app exists to avoid.
  const stale = updatedAt > 0 && now - updatedAt > 24 * 60 * 60_000;
  const history = pref('history');
  // A month, against her own total only — the history never recorded the household's.
  const change = useMemo(() => changeOver(history, Math.trunc(Date.now() / DAY_MS), 30, totals.toman), [history, totals.toman]);
  const shownChange = familyMode ? null : change;
  const figure = faCompact(total, 3, true);
  const words = faWordsToman(total);
  const failed = error != null && updatedAt === 0;
  // Anything worth a caution sentence dims the dot too: an all-clear green beside «اتصال نشد» contradicts the words.
  const trouble = failed || stale || error != null;

  return (
    <div class="home-hero">
      <HeroPanel>
        <div class="label-row">
          {familyAssets.length === 0
            ? <span class="label">جمع دارایی‌هات</span>
            : <HeroScopeToggle family={familyMode} onSelect={setFamilyTotal} />}
          {usd != null && (
            // "$" outside the isolate, so bidi puts it on the reading side of digits or a magnitude.
            <span class="usd figure" aria-label={`حدود ${faRate(usd)} دلار`}>{`≈ $${bidi(faRate(usd))}`}</span>
          )}
        </div>
        {/* Keyed on the figure: every refresh lifts the new one into place, the receipt that it did something. */}
        <HeroFigure key={figure} figure={figure} />
        {/* Digits are quick to scan but easy to misread by a factor of ten; the words are the check. */}
        {words && <p class="words">{words}</p>}
        <p class="full figure">{`${faNumber(total)} تومان`}</p>
        {shownChange ? <ChangePill change={shownChange} onClick={() => openReport('ASSETS')} />
          // Before thirty days there is no honest figure; the slot keeps its target and drops the number.
          : !portfolio && <ReportLink onClick={() => openReport('ASSETS')} />}
        <div class="hero-strip">
          <span class={`dot${trouble ? ' trouble' : ''}`} />
          <span class={`grow${stale || failed ? ' warn' : ''}`}>
            {failed ? 'نرخ‌ها به‌روز نشدن. اینترنتت رو چک کن'
              : stale ? `نرخ‌ها قدیمی‌ان؛ ${faAgo(updatedAt, now)}`
                : `نرخ‌ها: ${faAgo(updatedAt, now)}`}
          </span>
        </div>
        {error != null && updatedAt > 0 && <p class="stale-note">اتصال نشد. نرخ‌های قبلی نشون داده می‌شن.</p>}
      </HeroPanel>
    </div>
  );
}

/**
 * Never wraps: «۳٫۲ میلیارد تومان» broken over two lines reads as a layout bug, so it shrinks to
 * fit between 28 and 60 instead. Three decimals here: the headline is the one figure she watches move.
 */
function HeroFigure({ figure }: { figure: string }) {
  const ref = useAutoSize<HTMLSpanElement>(60, 28, figure);
  return <span ref={ref} class="hero-total figure fig">{figure}<span class="unit">تومان</span></span>;
}

/** Whose money the headline counts, in the label's own slot and the field's own colours. */
function HeroScopeToggle({ family, onSelect }: { family: boolean; onSelect: (family: boolean) => void }) {
  return (
    <div class="scope" role="radiogroup">
      {[false, true].map((option) => (
        <button type="button" role="radio" aria-checked={option === family} onClick={() => onSelect(option)}>
          {option ? 'خانواده' : 'دارایی‌هات'}
        </button>
      ))}
    </div>
  );
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
function Story({ view }: { view: LedgerView }) {
  const reportExcluded = pref('reportExcluded');
  const story = useMemo<HomeStory>(() => buildStory(view.entries, view.bankTotalRial, tehranDay(Date.now()), {
    budgets: readPlans(view.allEntries, view.entries).budgets,
    // The one gate دخل و خرج reads: قرض و همسر are its shipped default, not a mechanism of their own.
    countPassThrough: true,
    excluded: new Set(reportExcluded),
    mineId: view.mineId,
  }), [view, reportExcluded]);
  const budget = story.attentionBudget != null;
  return (
    <>
      {story.month.transactions > 0 ? <MonthFlow story={story} onOpen={() => openReport('CASH_FLOW')} />
        : <div class="quiet-start"><QuietStart /></div>}
      {(story.headline || story.attention) && (
        // Side by side, each without its «why»: the sentence behind it is on the report.
        <div class="story">
          {story.headline && <InsightCard insight={story.headline} />}
          {story.attention && (
            // It leaves the screen, so it says where it goes — and that depends on what is asking.
            <InsightCard insight={story.attention} action={budget ? 'بودجه رو باز کن' : 'دفتر رو باز کن'}
              onAction={() => selectTab(budget ? 'BUDGET' : 'LEDGER')} />
          )}
        </div>
      )}
    </>
  );
}

/** What came in against what went out, as one object: the bar is the comparison. */
export function MonthFlow({ story, onOpen }: { story: HomeStory; onOpen: () => void }) {
  const month = story.month;
  const income = tomanOf(month.incomeRial);
  const spent = tomanOf(month.spentRial);
  return (
    <button type="button" class="month-flow" onClick={onOpen}
      aria-label={`این ماه: درآمد ${faCompact(income)} تومان، خرج ${faCompact(spent)} تومان. برای گزارش کامل باز کن`}>
      <span class="head"><span>این ماه</span><Chevron size={18} /></span>
      <span class="halves">
        <FlowHalf label="درآمد" rial={month.incomeRial} tone="var(--tertiary)" />
        <FlowHalf label="خرج" rial={month.spentRial} tone="var(--on-surface)" />
      </span>
      {(month.incomeRial > 0 || month.spentRial > 0) && <FlowBar incomeRial={month.incomeRial} spentRial={month.spentRial} />}
    </button>
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
              ? <Section title={KIND_FA[kind]} trailing={counted > 1 ? <span class="sub figure">{`${faCompact(sum.toman, 3, true)} تومان`}</span> : undefined} />
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
  const shownRate = rate != null && note == null && !valued ? faRate(rate) : null;
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
                : <RowAmount toman={amount * rate} unit="تومان" />}
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
      {shared.items.length === 0 && <p class="band-row muted" style={{ fontSize: '13px' }}>فعلاً چیزی برای نمایش نفرستاده.</p>}
      {shared.items.map((item, i) => (
        <div class="band-row" key={i}>
          <span class="grow"><RowTitle text={item.name} /></span>
          <RowAmount toman={item.toman} unit="تومان" />
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
      <p>از دکمه پایین پول نقد، دلار، طلا، سکه یا رمزارز اضافه کن تا جمعشون رو ببینی.</p>
    </div>
  );
}

// Stable components, so the shell keeps each tab mounted with its own scroll.
const HomeTab = () => <HomeList portfolio={false} />;
const AssetsTab = () => <HomeList portfolio />;
registerTab('HOME', { Component: HomeTab });
registerTab('ASSETS', { Component: AssetsTab });
