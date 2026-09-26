/**
 * Report.kt: the «گزارش» tab — دخل و خرج and دارایی as peers under one pinned switch, each
 * scrolling on its own — with the drill-down sheet behind every category and member bar, and the
 * sheet that chooses what the report leaves out. Home.kt's InsightCard, InsightGrid and QuietStart
 * live here too; home imports them.
 *
 * Every figure arrives worked out by reports.ts, so nothing on this screen can disagree with
 * anything else on it, and what she reads is what the tests assert.
 */
import './report.css';
import type { ComponentChildren } from 'preact';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks';
import { KIND_FA, compositionByKind } from './catalog';
import type { Kind } from './catalog';
import { CategoryIcon, categoryHue, customGlyphs, glyphOf } from './categoryIcon';
import type { CategoryGlyph } from './categoryIcon';
import { changeOver, currentEffective, currentList, currentTotals } from './data';
import type { Change } from './data';
import { ledger, useLedger } from './derived';
import type { LedgerView } from './derived';
import { setReportExcluded, useFamily } from './family';
import { MONTHS, bidi, faCompact, faDecimal, faNumber, faRate, tomanOf, usdOf } from './format';
import { isTotal, worthItAnswers, worthItSummary } from './goals';
import type { WorthItSummary } from './goals';
import { Chevron, TrendCaret } from './icons';
import { DAY_MS, jalaliOf, tehranDay } from './jalali';
import { closeSheet, openSheet, registerSheet, registerTab, reportIntent, useNav } from './nav';
import type { ReportMode } from './nav';
import { REPORT_SPANS, ReportSpan, buildCashFlow, categoryWindow, memberWindow, reportMonthOf } from './reports';
import type { CashFlowReport, Insight, MemberShare, PeriodReport, ReportRange } from './reports';
import { CAT_UNCATEGORISED } from './rules';
import { pref, rows } from './state';
import { FilterCategoryChip, MemberFace, TimelineRow, openTxn } from './timeline';
import { ChipChoice, Panel, PillButton, ScreenTitle, SegmentedChoice, Sheet, SheetLabel, SheetTitle } from './ui';

// ─────────────────────────── small shared pieces ───────────────────────────

/** Kotlin's `round`: half to even, so a share of exactly 12.5٪ prints as the phone prints it. */
function roundEven(x: number): number {
  const r = Math.round(x);
  return Math.abs(x % 1) === 0.5 && r % 2 !== 0 ? r - 1 : r;
}

/** `(rial.toFloat() / total).coerceIn(0f, 1f)` — in Float, as the rows compute it. */
const shareOf = (rial: number, total: number): number =>
  Math.min(Math.max(Math.fround(Math.fround(rial) / Math.fround(total)), 0), 1);

/** The category marks she picked, read once per ledger derive rather than once per row. */
let marks: { view: LedgerView; custom: Record<string, CategoryGlyph> } | null = null;
function custom(): Record<string, CategoryGlyph> {
  const view = ledger();
  if (marks?.view !== view) marks = { view, custom: customGlyphs(view.managedCategories) };
  return marks.custom;
}
const glyph = (name: string): CategoryGlyph => glyphOf(name, custom());
const hue = (name: string): string => categoryHue(name, custom());
const alpha = (color: string, percent: number): string => `color-mix(in srgb, ${color} ${percent}%, transparent)`;

/** Growth, in the colour every bank app in the country uses for it — the scheme's gain role. */
const INCOME_TINT = 'var(--tertiary)';
const SPEND_TINT = 'var(--on-surface-variant)';

/**
 * BasicText's autoSize: the largest size in [min, max] at which the text fits its box on
 * [lines] lines, measured — a figure that shrinks rather than wraps or ellipsises, which is the
 * rule every amount on this screen keeps. One line scales in one step (width is linear in the
 * size); two lines step down a pixel at a time.
 */
function Fit({ text, min, max, lines = 1, class: cls = '', color }: {
  text: string; min: number; max: number; lines?: number; class?: string; color?: string;
}) {
  const ref = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    const el = ref.current!;
    let width = -1;
    const fit = () => {
      // Hidden (another tab, the other report) measures nothing; it refits when shown.
      if (!el.clientWidth || el.clientWidth === width) return;
      width = el.clientWidth;
      let size = max;
      el.style.fontSize = `${size}px`;
      if (lines === 1) {
        if (el.scrollWidth > width) size = Math.max(min, Math.floor(((max * width) / el.scrollWidth) * 4) / 4);
      } else {
        while (size > min && (el.scrollWidth > width || el.scrollHeight > size * 1.3 * lines + 1)) size--;
      }
      el.style.fontSize = `${size}px`;
    };
    fit();
    const watch = new ResizeObserver(fit);
    watch.observe(el);
    return () => watch.disconnect();
  }, [text, min, max, lines]);
  return <span ref={ref} class={`rp-fit${lines > 1 ? ' wrap' : ''} ${cls}`} style={{ color }}>{text}</span>;
}

/** A category's mark on its own disc, as everywhere a category leads a row. */
function CategoryDisc({ name, size, icon, stroke, fill = 16, ink = 100, color }: {
  name: string; size: number; icon: number; stroke?: number; fill?: number; ink?: number; color?: string;
}) {
  const tone = color ?? hue(name);
  return (
    <span class="disc" style={{ width: `${size}px`, height: `${size}px`, background: alpha(tone, fill) }}>
      <CategoryIcon glyph={glyph(name)} size={icon} stroke={stroke} color={ink === 100 ? tone : alpha(tone, ink)} />
    </span>
  );
}

// ─────────────────────────── insights (Home.kt) ───────────────────────────

const INSIGHT_ACCENT: Record<Insight['tone'], string> = {
  GOOD: 'var(--tertiary)',
  // Not tertiary: in dark theme that is the same green income speaks in, so the one thing
  // asking for her read as a deposit.
  ATTENTION: 'var(--primary)',
  NEUTRAL: 'var(--on-surface-variant)',
};

/**
 * One statement, and — only where there is somewhere to go — the control that goes there.
 * [why] says where the number came from, unasked; [action] names where the pill goes.
 */
export function InsightCard({ insight, why = false, action, onAction, class: cls = '' }: {
  insight: Insight; why?: boolean; action?: string; onAction?: () => void; class?: string;
}) {
  return (
    <Panel class={`rp-insight ${cls}`}>
      <div class="rp-insight-head">
        <span class="rp-dot" style={{ background: INSIGHT_ACCENT[insight.tone] }} />
        <p>{insight.text}</p>
      </div>
      {why && <p class="rp-insight-why">{insight.why}</p>}
      {action && <button type="button" class="rp-insight-action" onClick={onAction}>{action}</button>}
    </Panel>
  );
}

/**
 * Insights two to a row wherever two fit (each at least 168px), one per row below that; the row
 * takes the taller card's height, and an odd card keeps its half rather than stretching.
 */
export function InsightGrid({ insights }: { insights: Insight[] }) {
  return <div class="rp-insights">{insights.map((i) => <InsightCard key={i.text} insight={i} why />)}</div>;
}

/** Before there is any history, say so plainly rather than showing a zero. */
export function QuietStart({ named = 'این ماه' }: { named?: string }) {
  // The phone's smsEnabled branch, with the one word the browser cannot say: messages arrive
  // here by being pasted, not by themselves.
  return (
    <Panel>
      <p class="rp-panel-title">{`هنوز برای ${named} تراکنشی ثبت نشده`}</p>
      <p class="rp-panel-body">اولین پیامک بانکی که بچسبونی، اینجا پر می‌شه.</p>
    </Panel>
  );
}

// ─────────────────────────── the tab ───────────────────────────

const MODES: ReportMode[] = ['CASH_FLOW', 'ASSETS'];
const MODE_FA: Record<ReportMode, string> = { CASH_FLOW: 'دخل و خرج', ASSETS: 'دارایی' };

/** Which report, and the window دخل و خرج reads — Ui.kt's reportMode / reportMonth / reportSpan. */
interface Standing { stamp: number; mode: ReportMode; anchor: number | null; span: ReportSpan }
/**
 * openReport: every door into the report names which of the two it opens, and lands on this
 * month at «۱ ماه» — finding a year's figures because that is where the picker was left last
 * week is the door having lied. A null anchor is «the month containing today», always today's.
 */
const arrived = (): Standing => ({ stamp: reportIntent.stamp, mode: reportIntent.mode, anchor: null, span: ReportSpan.MONTH });

function ReportScreen() {
  useNav();
  const [kept, keep] = useState(arrived);
  const at = kept.stamp === reportIntent.stamp ? kept : arrived();
  useEffect(() => { if (at !== kept) keep(at); });

  // One scroll position per report, kept apart on purpose: switching to دارایی and finding it
  // scrolled to wherever دخل و خرج happened to be is the switch losing her place.
  const scroller = useRef<HTMLDivElement>(null);
  const scrolls = useRef<Record<ReportMode, number>>({ CASH_FLOW: 0, ASSETS: 0 });
  useLayoutEffect(() => { if (scroller.current) scroller.current.scrollTop = scrolls.current[at.mode]; }, [at.mode]);
  const onMode = (mode: ReportMode) => {
    scrolls.current[at.mode] = scroller.current?.scrollTop ?? 0;
    keep({ ...at, mode });
  };

  return (
    <div class="rp">
      {/* Pinned above the scroll: which report she is reading has to stay answerable at the
          bottom of a long one, and the gap under it is outside the scroll so content never
          slides up to touch the control that governs it. */}
      <div class="rp-top">
        <div class="screen-head"><ScreenTitle>گزارش‌ها</ScreenTitle></div>
        <SegmentedChoice options={MODES} selected={at.mode} label={(m) => MODE_FA[m]} onSelect={onMode} />
      </div>
      <div class="rp-scroll" ref={scroller}>
        {at.mode === 'CASH_FLOW'
          ? <CashFlowReportContent anchor={at.anchor} span={at.span} onWindow={(day, span) => keep({ ...at, anchor: day, span })} />
          : <AssetReportContent />}
      </div>
    </div>
  );
}

// ─────────────────────────── دارایی ───────────────────────────

/**
 * «بیشتر شده یا کمتر؟» over a window she picks — balance change, not profit and loss: deposits
 * count as growth here, exactly as they do at the bank.
 */
const WINDOWS: Array<[label: string, days: number | null]> = [
  ['۱ هفته', 7], ['۱ ماه', 30], ['۳ ماه', 91], ['۶ ماه', 182], ['۱ سال', 365],
  ['همه', null], // since the first recorded day
];

/** The verdict's colour, shared by the figure, its pill and the line. Words carry it; colour confirms. */
function changeTone(change: Change): string {
  if (Math.abs(change.delta) < 1) return 'var(--on-surface-variant)';
  // tertiary is the gain role: primary would set «بیشتر» in the colour of every button.
  return change.delta > 0 ? 'var(--tertiary)' : 'var(--error)';
}

function AssetReportContent() {
  useLedger(); // the bank row in the live total is the ledger's
  const history = pref('history');
  const current = currentTotals().toman;
  const composition = compositionByKind(currentList(), pref('rates')?.coins ?? [], currentEffective());
  // The history is keyed by UTC epoch day, as the phone's `today()` here is.
  const now = Math.trunc(Date.now() / DAY_MS);
  const days = Object.keys(history).map(Number).sort((a, b) => a - b);
  const first = days[0];

  const available = (reach: number | null): boolean =>
    !days.length ? reach == null
      : reach == null ? true
        // Covered when the history reaches (almost) back to the window's edge.
        : first <= now - reach + 3;

  // The shortest window that is at least a month: a week was never what the door was opened to ask.
  const [selected, select] = useState(() => WINDOWS.find(([, d]) => d !== 7 && available(d))?.[0] ?? 'همه');
  const reach = WINDOWS.find(([label]) => label === selected)![1];

  const change: Change | null = !days.length ? null
    : reach == null
      ? { delta: current - history[first], percent: history[first] > 0 ? (current - history[first]) / history[first] * 100 : null, sinceDay: first }
      : changeOver(history, now, reach, current);
  // The window's snapshots, closed with the live total as today's point.
  const points: Array<[number, number]> = change
    ? [...days.filter((d) => d >= change.sinceDay && d < now).map((d): [number, number] => [d, history[d]]), [now, current]]
    : [];

  return (
    <div>
      {/* A window the history is too short to answer is dimmed, not hidden — its absence is data. */}
      <ChipChoice options={WINDOWS.map(([label]) => label)} selected={selected} label={(l) => l}
        enabled={(l) => available(WINDOWS.find(([label]) => label === l)![1])} onSelect={select} scroll />
      <div class="rp-xxl">
        {change == null || points.length < 2 ? <EmptyReport /> : (
          <>
            <ChangeFigure change={change} windowLabel={selected} now={now} />
            <div class="rp-chart-card">
              <HistoryChart points={points} tone={changeTone(change)} />
              {/* Time flows left to right inside the chart, so in this RTL row the first
                  caption lands on the right — under the newest end of the line. */}
              <div class="rp-captions">
                <ChartCaption label="اکنون" value={current} />
                <ChartCaption label="شروع" value={history[change.sinceDay]} />
              </div>
            </div>
            <p class="rp-footnote">هر روز یک نقطه ثبت می‌شه، حتی اگه برنامه رو باز نکنی.</p>
          </>
        )}
      </div>
      {/* One kind is the hero total said twice. */}
      {composition.length > 1 && <div class="rp-xxl"><CompositionBar composition={composition} /></div>}
    </div>
  );
}

/**
 * The bar's own palette, fixed so it holds on both themes: the icon discs' container tints all
 * but vanish on the dark background. Gold stays reserved for the gold kinds.
 */
const BAR_TINT: Record<Kind, string> = {
  CASH: '#2F7D3F', FIAT: '#79B989', CRYPTO: '#A3BBA6', GOLD: '#F7C948',
  SILVER: '#AEB6BD', COIN: '#C99B2C', STOCK: '#4C8A5E', PROPERTY: '#8C7B6B',
};

/** What the total is made of, right now, in the home list's own sections. */
function CompositionBar({ composition }: { composition: Array<[Kind, number]> }) {
  const total = composition.reduce((s, [, v]) => s + v, 0);
  return (
    <div>
      <h2 class="rp-heading">ترکیب دارایی</h2>
      <div class="rp-composition">
        {composition.map(([kind, value]) => <span style={{ flexGrow: value, background: BAR_TINT[kind] }} />)}
      </div>
      {composition.map(([kind, value]) => (
        <div class="rp-legend">
          <span class="rp-legend-dot" style={{ background: BAR_TINT[kind] }} />
          <span class="grow">{KIND_FA[kind]}</span>
          {/* Parentheses, not a middot: beside Persian digits «·» reads as a zero. */}
          <span class="muted">{`${faCompact(value, 3, true)} تومان (${faNumber(roundEven(value / total * 100))}٪)`}</span>
        </div>
      ))}
    </div>
  );
}

/** Bigger or smaller, by how much, since when — stacked the way the hero total is. */
function ChangeFigure({ change, windowLabel, now }: { change: Change; windowLabel: string; now: number }) {
  const gained = change.delta > 0;
  const flat = Math.abs(change.delta) < 1;
  const tone = changeTone(change);
  const since = windowLabel === 'همه' ? `از ${faNumber(now - change.sinceDay)} روز پیش` : `نسبت به ${windowLabel} پیش`;
  return (
    <div class="rp-fitbox rp-change">
      <p class="rp-since">{since}</p>
      <Fit text={flat ? 'بدون تغییر' : `${faCompact(Math.abs(change.delta), 3, true)} تومان`} min={22} max={44}
        class="figure rp-w900" color={tone} />
      {!flat && (
        <>
          <div class="rp-verdict">
            <span class="rp-verdict-pill" style={{ background: alpha(tone, 14), color: tone }}>
              <TrendCaret up={gained} color={tone} size={10} />
              <span class="figure">{change.percent != null ? `${faDecimal(Math.abs(change.percent), 1)}٪` : gained ? 'بیشتر' : 'کمتر'}</span>
            </span>
            <span class="muted">{gained ? 'بیشتر شده' : 'کمتر شده'}</span>
          </div>
          <p class="figure muted rp-change-exact">{`${faNumber(Math.abs(change.delta))} تومان`}</p>
        </>
      )}
    </div>
  );
}

function ChartCaption({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <p class="rp-caption-label">{label}</p>
      <p class="figure rp-caption-value">{`${faCompact(value)} تومان`}</p>
    </div>
  );
}

function EmptyReport() {
  return (
    <div class="rp-empty">
      {/* The tab bar's own three bars — the empty state introduces the chart in its own pen. */}
      <span class="disc">
        <svg width="36" height="36" viewBox="0 0 36 36" aria-hidden="true">
          {[0.55, 0.28, 0].map((top, i) => {
            const x = 36 * (0.14 + i * 0.36);
            return <path d={`M${x} ${36 * top + 1} L${x} 35`} stroke="currentColor" stroke-width={2} stroke-linecap="round" />;
          })}
        </svg>
      </span>
      <p class="rp-empty-title">هنوز نموداری نیست</p>
      <p class="rp-empty-body">از امروز هر روز یک نقطه ثبت می‌شه و نمودار کم‌کم کامل می‌شه.</p>
    </div>
  );
}

/**
 * A plain area line — the shape of the money over time, left to right even in RTL, in the
 * verdict's colour so the line and the figure above it can never disagree. Drawn on a stretched
 * 1000-wide box with an unscaled pen; today's marker sits on the right edge as a round element.
 */
function HistoryChart({ points, tone }: { points: Array<[number, number]>; tone: string }) {
  const W = 1000;
  const H = 220;
  let minV = Infinity;
  let maxV = -Infinity;
  for (const [, v] of points) { minV = Math.min(minV, v); maxV = Math.max(maxV, v); }
  const span = maxV - minV > 0 ? maxV - minV : 1;
  const d0 = points[0][0];
  const dSpan = Math.max(points[points.length - 1][0] - d0, 1);
  const padY = H * 0.12;
  const px = (day: number) => ((day - d0) / dSpan) * W;
  // A flat history draws mid-height: at the bottom edge an unchanged total reads like a crash to zero.
  const py = (v: number) => (maxV === minV ? H / 2 : H - padY - ((v - minV) / span) * (H - 2 * padY));
  const line = points.map(([d, v], i) => `${i ? 'L' : 'M'}${px(d).toFixed(2)} ${py(v).toFixed(2)}`).join(' ');
  return (
    <div class="rp-history">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" width="100%" height={H} aria-hidden="true">
        <defs>
          <linearGradient id="rp-history-fill" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2={H}>
            <stop offset="0" style={{ stopColor: tone, stopOpacity: 0.28 }} />
            <stop offset="1" style={{ stopColor: tone, stopOpacity: 0 }} />
          </linearGradient>
        </defs>
        <path d={`${line} L${W} ${H} L0 ${H} Z`} fill="url(#rp-history-fill)" />
        <path d={line} fill="none" style={{ stroke: tone }} stroke-width={3} stroke-linecap="round" stroke-linejoin="round"
          vector-effect="non-scaling-stroke" />
      </svg>
      {/* Ringed in the card's own colour, so it reads as a marker on the line rather than the line gone fat. */}
      <span class="rp-history-end" style={{ top: `${py(points[points.length - 1][1])}px`, '--tone': tone }} />
    </div>
  );
}

// ─────────────────────────── دخل و خرج ───────────────────────────

type OnWindow = (day: number, span: ReportSpan) => void;

/**
 * The window she picked: what came in, what went out, what remained, how it sits against the
 * windows around it, where each side came from, and what changed. One walk over the ledger
 * (buildCashFlow) makes all of it, so the bar she taps and the figure she reads agree.
 */
function CashFlowReportContent({ anchor, span, onWindow }: { anchor: number | null; span: ReportSpan; onWindow: OnWindow }) {
  const view = useLedger();
  const family = useFamily();
  const today = tehranDay(Date.now());
  const excludedIds = pref('reportExcluded');
  const excluded = useMemo(() => new Set(excludedIds), [excludedIds]);
  const cash = useMemo(() => buildCashFlow(view.entries, view.bankTotalRial, today, reportMonthOf(anchor ?? today), {
    span,
    // The exclusion set is the one gate: قرض و همسر ride it as the shipped default.
    countPassThrough: true,
    excluded,
    // «۱ هفته» is the one span that needs the day itself.
    anchorDay: anchor,
  }), [view, today, anchor, span, excluded]);
  // Her «ارزش داشت؟» answers over exactly this window — a summary ignoring the picker would be
  // the one section describing different months from every figure around it.
  const worthIt = useMemo(
    () => worthItSummary(view.entries.filter((e) => cash.range.contains(e.txn.day)), worthItAnswers(rows('decisions'))),
    [cash, view],
  );
  // Today's rate prices the window she is standing in; a closed one is frozen at its own days.
  const usdRate = currentEffective().usd ?? null;
  const period = cash.period;
  // A window whose only rows are held apart is not empty — those rows are listed under دسته‌ها.
  const heldApart = period.excludedSpending.length > 0 || period.excludedIncome.length > 0;
  const cards = [...cash.insights, ...cash.wins];

  return (
    <div>
      {/* The anchor handed over is the window's last day: a day of the month she is reading for
          month spans, and the day whose week she should land in for «۱ هفته». */}
      <ChipChoice options={[...REPORT_SPANS]} selected={cash.span} label={(s) => s.fa} enabled={(s) => cash.spans.includes(s)}
        onSelect={(s) => onWindow(cash.range.endDay - 1, s)} scroll />
      <div class="rp-xxl"><WindowNavigator cash={cash} onWindow={onWindow} /></div>
      <div class="rp-m">
        {period.transactions === 0 && !heldApart
          // This window empty yet is a thing to do; a past one may simply be older than the ledger.
          ? cash.current ? <QuietStart named={cash.range.recentFa} /> : <EmptyMonth range={cash.range} />
          : <MonthSummary month={period} current={cash.current} today={cash.today} usdRate={usdRate} rateHistory={pref('rateHistory')} />}
      </div>
      {/* Two windows with something in them is the least a comparison can be made of. */}
      {cash.series.filter((r) => r.incomeRial > 0 || r.spentRial > 0).length > 1 && (
        <div class="rp-xxl"><MonthBars cash={cash} onWindow={onWindow} /></div>
      )}
      {(period.transactions > 0 || heldApart) && <div class="rp-xxl"><CategoryDetail period={period} /></div>}
      {cash.members.length > 0 && <div class="rp-xxl"><MemberDetail members={cash.members} period={period} excluded={excluded} /></div>}
      {/* Present even when the window is empty while an exclusion stands: a report quietly
          missing a category with no way to see why is the friendlier kind of lie. */}
      {view.categories.length > 0 && (
        <div class="rp-l">
          <ReportExclusions excluded={excludedIds} householdShared={family.paired}
            capsTotal={rows('goals').some((g) => !g.deleted && isTotal(g))} />
        </div>
      )}
      {worthIt.total > 0 && <div class="rp-xxl"><WorthItDetail summary={worthIt} range={cash.range} /></div>}
      {/* Findings and wins in one grid: paired by kind, each run could end on a lone card. */}
      {cards.length > 0 && <div class="rp-xxl"><InsightGrid insights={cards} /></div>}
    </div>
  );
}

/**
 * Which window she is reading, and the two steps either side of it: the name leads at a
 * heading's weight, the stepper trails. The title is the way back to today when she has walked
 * away from it. A step is one month even over a year (a week over «۱ هفته»), and an arrow with
 * nowhere to go is dimmed, never hidden.
 */
function WindowNavigator({ cash, onWindow }: { cash: CashFlowReport; onWindow: OnWindow }) {
  const weekly = cash.range.week != null;
  const at = jalaliOf(cash.today);
  // The day, not the month: «۱ هفته» anchors on it.
  const back = () => onWindow(cash.today, cash.span);
  const backFa = weekly ? 'برگشت به این هفته' : 'برگشت به این ماه';
  return (
    <div class="rp-nav">
      <div class={`rp-fitbox rp-nav-title${cash.current ? '' : ' away'}`} onClick={cash.current ? undefined : back}>
        {/* Two lines where there are two ends to name, broken at «تا». */}
        <h2><Fit text={cash.range.fa} min={16} max={26} lines={cash.range.count === 1 && !weekly ? 1 : 2} class="figure rp-w800" /></h2>
        {cash.current
          ? <p class="rp-nav-sub muted">{`تا امروز ${faNumber(at.day)} ${MONTHS[at.month - 1]}`}</p>
          : <button type="button" class="rp-btn rp-nav-sub primary-ink" onClick={(e) => { e.stopPropagation(); back(); }}>{backFa}</button>}
      </div>
      <StepButton label={weekly ? 'هفتهٔ قبل' : 'ماه قبل'} enabled={cash.canGoBack} previous
        onClick={() => onWindow(weekly ? cash.range.startDay - 7 : cash.selected.previous().startDay, cash.span)} />
      <StepButton label={weekly ? 'هفتهٔ بعد' : 'ماه بعد'} enabled={cash.canGoForward}
        onClick={() => onWindow(weekly ? cash.range.startDay + 7 : cash.selected.next().startDay, cash.span)} />
    </div>
  );
}

/** A 40px well inside a 48px target, dimmed with the button so a spent arrow looks spent. */
function StepButton({ label, enabled, previous = false, onClick }: { label: string; enabled: boolean; previous?: boolean; onClick: () => void }) {
  return (
    <button type="button" class="rp-step" aria-label={label} disabled={!enabled} onClick={onClick}>
      {/* «قبل» points the way the page reads backwards: right, in RTL. */}
      <span><Chevron size={24} back={previous} /></span>
    </button>
  );
}

/** A window the ledger has nothing for — which is not the same as one in which nothing happened. */
function EmptyMonth({ range }: { range: ReportRange }) {
  return (
    <Panel>
      <p class="rp-panel-title">{`در ${range.fa} تراکنشی ثبت نشده`}</p>
      {/* The phone's line, with the pasted message in place of messages switched on. */}
      <p class="rp-panel-body">دفترت از اولین پیامکی که چسبوندی پر می‌شه، پس ممکنه این ماه قبل از اون باشه.</p>
    </Panel>
  );
}

/**
 * What came in against what went out, then the one line that settles it. A deficit is never a
 * negative amount under the word «مانده»: the word carries it, «کسری», and colour confirms.
 */
function MonthSummary({ month, current, today, usdRate, rateHistory }: {
  month: PeriodReport; current: boolean; today: number; usdRate: number | null; rateHistory: Record<string, number>;
}) {
  // Today's rate only for the window still being written; one that is over is read at the rates
  // recorded while it ran, and one from before the app kept rates shows no dollars at all.
  const rate = current ? usdRate : month.range.usdRate(rateHistory);
  // «درآمد این ماه» on the month she is standing in; on a longer window just «درآمد».
  const named = current && month.range.count === 1 ? ` ${month.range.recentFa}` : '';
  const kept = month.netRial >= 0;
  const tone = kept ? INCOME_TINT : 'var(--error)';
  const net = Math.abs(month.netRial);
  return (
    <Panel>
      <div class="rp-sides">
        <FlowSide label={`درآمد${named}`} rial={month.incomeRial} tone={INCOME_TINT} rate={rate} />
        <FlowSide label={`خرج${named}`} rial={month.spentRial} tone="var(--on-surface)" rate={rate} />
      </div>
      {(month.incomeRial > 0 || month.spentRial > 0) && (
        <FlowSplit parts={[[month.incomeRial, INCOME_TINT], [month.spentRial, SPEND_TINT]]} />
      )}
      <hr class="rp-rule rp-l" />
      <div class="rp-net">
        <span class="muted">{kept ? `مانده${named}` : `کسری${named}`}</span>
        {/* The figure yields, not the label: it is the half that grows by orders of magnitude. */}
        <div class="rp-fitbox grow"><Fit text={bidi(faCompact(tomanOf(net)))} min={15} max={22} class="figure rp-w800 rp-end" color={tone} /></div>
      </div>
      <p class="figure muted rp-net-exact">{`${faNumber(tomanOf(net))} تومان`}</p>
      <UsdAside rial={net} rate={rate} end />
      <SpendPace month={month} today={today} />
    </Panel>
  );
}

/**
 * The pace behind the خرج figure, per day and per week — what makes windows of different
 * lengths comparable. Absent where the window is too short for «میانگین» to be true.
 */
function SpendPace({ month, today }: { month: PeriodReport; today: number }) {
  const daily = month.dailySpendRial(today);
  if (daily == null) return null;
  const weekly = month.weeklySpendRial(today);
  return (
    <>
      <hr class="rp-rule rp-m" />
      <PaceLine label="میانگین خرج روزانه" rial={daily} />
      {weekly != null && <PaceLine label="میانگین خرج هفتگی" rial={weekly} />}
    </>
  );
}

function PaceLine({ label, rial }: { label: string; rial: number }) {
  return (
    <div class="rp-pace">
      <span class="muted">{label}</span>
      {/* Compact only: the exact Rial of an average is precision the division never had. */}
      <div class="rp-fitbox grow"><Fit text={bidi(`${faCompact(tomanOf(rial))} تومان`)} min={11} max={14} class="figure rp-w700 rp-end" /></div>
    </div>
  );
}

/** One side of the window: the magnitude to read at a glance, the exact figure under it. */
function FlowSide({ label, rial, tone, rate }: { label: string; rial: number; tone: string; rate: number | null }) {
  return (
    <div class="rp-fitbox rp-side">
      <Fit text={label} min={10} max={12} class="muted" />
      <Fit text={bidi(faCompact(tomanOf(rial)))} min={13} max={26} class="figure rp-w800 rp-side-figure" color={tone} />
      {/* faCompact truncates, so the magnitude is never more than she has; the exact is right here. */}
      <Fit text={`${faNumber(tomanOf(rial))} تومان`} min={8} max={11} class="figure muted rp-side-exact" />
      <UsdAside rial={rial} rate={rate} />
    </div>
  );
}

/** The same figure in dollars, as an aside at the exact figure's size; absent without a rate. */
function UsdAside({ rial, rate, end = false }: { rial: number; rate: number | null; end?: boolean }) {
  const usd = usdOf(tomanOf(rial), rate);
  if (usd == null) return null;
  return (
    <p class={`rp-usd figure muted${end ? ' rp-end' : ''}`}>
      {/* The isolate keeps the number one run; «$» sits outside it on the reading side. */}
      <span aria-hidden="true">{`≈ $${bidi(faRate(usd))}`}</span>
      <span class="sr">{`حدود ${faRate(usd)} دلار`}</span>
    </p>
  );
}

/**
 * Lengths off one baseline, in the order the figures above are read. Floored at 6٪ so a small
 * side draws as «a little» rather than a dot; every exact figure is stated above it.
 */
function FlowSplit({ parts }: { parts: Array<[number, string]> }) {
  const shown = parts.filter(([rial]) => rial > 0);
  if (!shown.length) return null;
  const total = shown.reduce((s, [rial]) => s + rial, 0);
  return (
    <div class="rp-split rp-l">
      {shown.map(([rial, tone]) => <span style={{ flexGrow: Math.max(rial / total, 0.06), background: tone }} />)}
    </div>
  );
}

/** The shortest a bar may be drawn while still reading as an amount rather than as nothing. */
const BAR_FLOOR = 0.03;

/**
 * Up to six windows side by side on one scale — scaling each to itself would call a two-million
 * month and a two-hundred-million month equal. The newest lands on the left, beside «ماه بعد».
 * Each column is a control: tapping reads that month alone, with the span dropping to «۱ ماه»
 * visibly (a week stays a week).
 */
function MonthBars({ cash, onWindow }: { cash: CashFlowReport; onWindow: OnWindow }) {
  const weekly = cash.range.week != null;
  const top = Math.max(...cash.series.map((r) => Math.max(r.incomeRial, r.spentRial)), 1);
  // A year of months thins the bars to keep twelve on a narrow phone; the numerals fit anyway.
  const barWidth = cash.series.length > 8 ? 7 : 12;
  // Only worth marking where there is something outside the window to tell it apart from.
  const markWindow = !weekly && cash.series.length > cash.range.count;
  return (
    <div>
      <div class="rp-row-head">
        {/* Not «شش ماه اخیر»: near the ledger's start the window holds fewer. */}
        <h2 class="rp-heading grow">{weekly ? 'هفته به هفته' : 'ماه به ماه'}</h2>
        <BarKey label="درآمد" tone={INCOME_TINT} />
        <BarKey label="خرج" tone={SPEND_TINT} />
      </div>
      <div class="rp-bars" role="tablist">
        {cash.series.map((report) => {
          // Read alone is a selection; counted into a longer window is not. A week is compared
          // by its own start day, since several weeks share a month.
          const only = weekly ? report.range.startDay === cash.range.startDay
            : cash.range.count === 1 && report.month.equals(cash.selected);
          const inWindow = cash.range.containsMonth(report.month);
          const label = `${weekly ? report.range.fa : report.month.fa}: ` +
            `درآمد ${faCompact(tomanOf(report.incomeRial))} تومان، خرج ${faCompact(tomanOf(report.spentRial))} تومان` +
            (inWindow && markWindow ? '، در بازهٔ انتخاب‌شده' : '');
          return (
            <button type="button" role="tab" aria-selected={only} aria-label={label}
              class={`rp-bar-col${only || (inWindow && markWindow) ? ' marked' : ''}${only ? ' only' : ''}`}
              onClick={() => onWindow(report.range.startDay, weekly ? ReportSpan.WEEK : ReportSpan.MONTH)}>
              <span class="rp-bar-box">
                <Bar rial={report.incomeRial} top={top} tone={INCOME_TINT} width={barWidth} />
                <Bar rial={report.spentRial} top={top} tone={SPEND_TINT} width={barWidth} />
              </span>
              {/* The month's number, not its name: «فرورد…» lost the syllable telling it apart.
                  A week is named by the day its شنبه falls on. */}
              <span class="figure rp-bar-label">
                {faNumber(weekly ? jalaliOf(report.range.startDay).day : report.month.month)}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** One bar against the chart's ceiling. Nothing at all is what nothing looks like. */
function Bar({ rial, top, tone, width }: { rial: number; top: number; tone: string; width: number }) {
  const height = rial > 0 ? Math.min(Math.max(Math.fround(rial) / top, BAR_FLOOR), 1) : 0;
  return <span class="rp-bar" style={{ width: `${width}px`, height: `${height * 100}%`, background: rial > 0 ? tone : 'transparent' }} />;
}

function BarKey({ label, tone }: { label: string; tone: string }) {
  return <span class="rp-key"><span style={{ background: tone }} />{label}</span>;
}

type Lens = 'EXPENSE' | 'INCOME';
const LENSES: Lens[] = ['EXPENSE', 'INCOME'];
const LENS_FA: Record<Lens, string> = { EXPENSE: 'خرج', INCOME: 'درآمد' };

/** What the drill-down sheet is asked to open: one category, one held-apart category, or one member. */
interface ReportCategoryProps {
  name: string;
  income: boolean;
  range: ReportRange;
  /** The side's figure the share is of; zero for a held-apart category, which is inside no total. */
  sideRial: number;
  member?: MemberShare;
  countPassThrough?: boolean;
  excluded?: ReadonlySet<string>;
}
const openCategory = (props: ReportCategoryProps): void => openSheet('reportCategory', props as unknown as Record<string, unknown>);

/**
 * Where each side of the window came from — both sides, and not truncated: this is the screen she
 * came to for the detail. Money the report is deliberately not counting is listed under the
 * counted rows rather than hidden.
 */
function CategoryDetail({ period }: { period: PeriodReport }) {
  // خرج unless there is nothing on that side, counted or held apart.
  const [side, setSide] = useState<Lens>(() =>
    period.spentRial <= 0 && !period.excludedSpending.length && (period.incomeRial > 0 || period.excludedIncome.length > 0)
      ? 'INCOME' : 'EXPENSE');
  const income = side === 'INCOME';
  const list = income ? period.incomeByCategory : period.spendingByCategory;
  const total = income ? period.incomeRial : period.spentRial;
  const heldOut = income ? period.excludedIncome : period.excludedSpending;
  // The window written out, never «این ماه»: a fact about named months.
  const named = period.range.fa;
  return (
    <div>
      <div class="rp-row-head">
        <h2 class="rp-heading grow">دسته‌ها</h2>
        <ChipChoice options={LENSES} selected={side} label={(l) => LENS_FA[l]} onSelect={setSide} />
      </div>
      {!list.length || total <= 0
        ? <p class="rp-none muted">{income ? `در ${named} درآمدی ثبت نشده.` : `در ${named} خرجی ثبت نشده.`}</p>
        : list.map(([name, rial]) => (
          <ShareRow key={name} name={name} rial={rial} total={total} tint={income ? INCOME_TINT : undefined}
            lead={<CategoryDisc name={name} size={32} icon={17} stroke={1.5} />}
            onOpen={() => openCategory({ name, income, range: period.range, sideRial: total })} />
        ))}
      {heldOut.length > 0 && (
        <>
          <h3 class="rp-aside-head">بیرون از جمع</h3>
          {heldOut.map(([name, rial]) => (
            <AsideCategoryRow key={name} name={name} rial={rial}
              // sideRial zero: this category is inside no total, so its sheet claims no share.
              onOpen={() => openCategory({ name, income, range: period.range, sideRial: 0 })} />
          ))}
        </>
      )}
    </div>
  );
}

/**
 * One row the report is not counting: [ShareRow] with the share and the bar taken off, dim on
 * purpose — the amount is stated, not weighed. The share's well is kept empty so both lists'
 * amounts sit on one column.
 */
function AsideCategoryRow({ name, rial, onOpen }: { name: string; rial: number; onOpen: () => void }) {
  return (
    <button type="button" class="rp-btn rp-share" onClick={onOpen}
      aria-label={`${name}: ${faCompact(tomanOf(rial))} تومان، در جمع حساب نشده`}>
      <span class="rp-share-row">
        <CategoryDisc name={name} size={32} icon={17} stroke={1.5} fill={10} ink={70} />
        <span class="rp-share-name muted">{name}</span>
        <span class="figure rp-share-amount">{bidi(faCompact(tomanOf(rial)))}</span>
        <span class="rp-share-pct" />
        <span class="muted"><Chevron size={18} /></span>
      </span>
    </button>
  );
}

/**
 * The window split by who each row belongs to — «دسته‌ها» grouped the other way, in the same
 * two-lens construction: «خرج» by who paid, «درآمد» by who it reached.
 */
function MemberDetail({ members, period, excluded }: { members: MemberShare[]; period: PeriodReport; excluded: ReadonlySet<string> }) {
  const [side, setSide] = useState<Lens>(() =>
    members.every((m) => m.spentRial <= 0) && members.some((m) => m.incomeRial > 0) ? 'INCOME' : 'EXPENSE');
  const income = side === 'INCOME';
  const list = members
    .map((m): [MemberShare, number] => [m, income ? m.incomeRial : m.spentRial])
    .filter(([, rial]) => rial > 0)
    .sort((a, b) => b[1] - a[1]);
  const total = list.reduce((s, [, rial]) => s + rial, 0);
  const named = period.range.fa;
  return (
    <div>
      <div class="rp-row-head">
        <h2 class="rp-heading grow">اعضا</h2>
        <ChipChoice options={LENSES} selected={side} label={(l) => LENS_FA[l]} onSelect={setSide} />
      </div>
      {!list.length || total <= 0
        ? <p class="rp-none muted">{income ? `در ${named} درآمدی از اعضا ثبت نشده.` : `در ${named} خرجی از اعضا ثبت نشده.`}</p>
        : list.map(([member, rial]) => (
          <ShareRow key={member.id} name={member.name} rial={rial} total={total} tint={income ? INCOME_TINT : undefined}
            lead={<MemberFace name={member.name} avatar={member.avatar} size={32} />}
            // Her rows on the side she is reading, in the sheet the category rows open.
            onOpen={() => openCategory({
              name: member.name, income, range: period.range, sideRial: total, member,
              countPassThrough: period.countedPassThrough, excluded,
            })} />
        ))}
    </div>
  );
}

/**
 * One category (or member) as an amount and a share of its own side. The share is the figure
 * that survives inflation. The amount comes before the fixed-width share box, so the shares line
 * up on the box and the amounts on where it begins.
 */
function ShareRow({ name, rial, total, tint, lead, onOpen }: {
  name: string; rial: number; total: number; tint?: string; lead: ComponentChildren; onOpen?: () => void;
}) {
  const share = shareOf(rial, total);
  const percent = faNumber(roundEven(share * 100));
  const body = (
    <>
      <span class="rp-share-row">
        {lead}
        {/* Wraps rather than truncates: a clipped name is the one thing she cannot reconstruct. */}
        <span class="rp-share-name">{name}</span>
        <span class="figure rp-share-amount">{bidi(faCompact(tomanOf(rial)))}</span>
        <span class="figure rp-share-pct">{`${percent}٪`}</span>
        {onOpen && <span class="muted"><Chevron size={18} /></span>}
      </span>
      <span class="rp-track"><span style={{ width: `${share * 100}%`, background: tint ?? 'var(--primary)' }} /></span>
    </>
  );
  const label = `${name}: ${faCompact(tomanOf(rial))} تومان، ${percent} درصد`;
  return onOpen
    ? <button type="button" class="rp-btn rp-share" aria-label={label} onClick={onOpen}>{body}</button>
    : <div class="rp-share" role="group" aria-label={label}>{body}</div>;
}

/**
 * «به نظر خودت» — her «ارزش داشت؟» answers, summed over this window. Zero rows are skipped: a
 * Persian zero at this size is a floating dot, not a figure.
 */
function WorthItDetail({ summary, range }: { summary: WorthItSummary; range: ReportRange }) {
  return (
    <div>
      <h2 class="rp-heading">به نظر خودت</h2>
      <p class="rp-note rp-xs">{`جمع جواب‌هایی که به «ارزش داشت؟» دادی، در ${range.fa}.`}</p>
      <div class="rp-worth">
        {summary.worth > 0 && <WorthItLine label="ارزش داشت" rial={summary.worth} tone="var(--tertiary)" />}
        {summary.needed > 0 && <WorthItLine label="لازم بود" rial={summary.needed} tone="var(--on-surface-variant)" />}
        {/* The caution amber: her own «نه» is the one figure that asks to be acted on. */}
        {summary.regretted > 0 && <WorthItLine label="ارزش نداشت" rial={summary.regretted} tone="var(--secondary)" />}
      </div>
      {summary.regretted > 0 && (
        <p class="rp-note rp-s">«ارزش نداشت» تنها رقمیه که می‌شه روش کاری کرد — خرجیه که خودت گفتی می‌شد نباشه.</p>
      )}
    </div>
  );
}

function WorthItLine({ label, rial, tone }: { label: string; rial: number; tone: string }) {
  return (
    <div class="rp-worth-line">
      <span class="grow">{label}</span>
      <span class="figure rp-w700" style={{ color: tone }}>{bidi(`${faCompact(tomanOf(rial))} تومان`)}</span>
    </div>
  );
}

/**
 * What this report is deliberately not counting, and the door to changing that. The note is the
 * honesty half; its two clauses ride only where they apply — «کل خرج» when she keeps one (the
 * one figure off this screen the set governs), the household when the set is shared.
 */
function ReportExclusions({ excluded, capsTotal, householdShared }: { excluded: string[]; capsTotal: boolean; householdShared: boolean }) {
  const view = useLedger();
  const names = view.categories.filter((c) => excluded.includes(c.id)).map((c) => c.nameFa);
  return (
    <div>
      {names.length > 0 && (
        <p class="rp-note rp-exclusions">
          {`دسته‌های ${names.join('، ')} توی جمع‌های این گزارش حساب نشدن؛ ` +
            'خرج و درآمدی که داشتن رو جدا، ته فهرست دسته‌ها، می‌بینی.' +
            (capsTotal ? ' و زیر سقف کل خرجت هم نمی‌رن.' : '') +
            (householdShared ? ' این انتخاب بین اعضای خانواده مشترکه.' : '')}
        </p>
      )}
      <PillButton label={excluded.length ? 'ویرایش دسته‌های کنارگذاشته' : 'کنار گذاشتن دسته‌ها از گزارش'} onClick={() => openSheet('exclude')} />
    </div>
  );
}

// ─────────────────────────── the sheets ───────────────────────────

/**
 * One category or member, opened up: the figure, its share of its side, six months of it, and
 * the transactions it is made of — each a door to its own page, because a share bar is as
 * accountable to «چرا این را می‌بینم؟» as any other number here.
 */
function ReportCategorySheet({ name, income, range, sideRial, member, countPassThrough = false, excluded }: ReportCategoryProps) {
  const view = useLedger();
  const win = member
    ? memberWindow(view.entries, member, income, range, sideRial, countPassThrough, excluded)
    : categoryWindow(view.entries, name, income, range, sideRial);
  // A person's name through categoryHue would borrow a colour that means «خوراک» elsewhere, so a
  // member's sheet wears the colour their bar already wore.
  const tone = win.face == null ? hue(win.name) : income ? INCOME_TINT : 'var(--primary)';
  return (
    <Sheet label={win.name}>
      <div class="rp-sheet-head">
        {win.face != null ? <MemberFace name={win.name} avatar={win.face} />
          : <CategoryDisc name={win.name} size={44} icon={24} fill={18} color={tone} />}
        <div class="grow">
          <h2 class="rp-sheet-name">{win.name}</h2>
          <p class="muted rp-sheet-range">{win.range.fa}</p>
        </div>
      </div>
      <div class="rp-fitbox rp-l">
        <Fit text={bidi(`${faCompact(tomanOf(win.totalRial))} تومان`)} min={22} max={34} class="figure rp-w900"
          color={income ? INCOME_TINT : 'var(--on-surface)'} />
      </div>
      {win.share != null && (
        <p class="muted rp-sheet-share">{`${faNumber(roundEven(win.share * 100))}٪ از ${income ? 'درآمد' : 'خرج'} این بازه`}</p>
      )}
      {/* A member's money regrouped by where it went — the question their sheet is opened for. */}
      {win.breakdown.length > 0 && (
        <>
          <SheetLabel>دسته به دسته</SheetLabel>
          {win.breakdown.map(([cat, rial]) => (
            <ShareRow key={cat} name={cat} rial={rial} total={Math.max(win.totalRial, 1)} tint={income ? INCOME_TINT : undefined}
              lead={<CategoryDisc name={cat} size={32} icon={17} stroke={1.5} />} />
          ))}
        </>
      )}
      {win.trend.some(([, rial]) => rial > 0) && (
        <>
          <SheetLabel>ماه به ماه</SheetLabel>
          <CategoryTrend trend={win.trend} tone={tone} />
        </>
      )}
      {win.rows.length > 0 && (
        <>
          <SheetLabel>تراکنش‌های این بازه</SheetLabel>
          <div class="band">
            {/* A category's rows are all the one category, so its disc would be noise; a
                member's rows are not, and the disc is what says where each one went. */}
            {win.rows.map((e) => (
              // Tapping goes where it always goes; closing the page lands back on this report.
              <TimelineRow key={e.txn.ref} entry={e} showIcon={win.face != null} onClick={() => openFromSheet(e.txn.ref)} />
            ))}
          </div>
        </>
      )}
    </Sheet>
  );
}

/** Kotlin's `close { onDismiss(); onOpenEntry(e) }`: nav's openPage replaces the sheet with the page in one move. */
function openFromSheet(ref: string): void {
  openTxn(ref);
}

/**
 * Six months of one category on one scale, in its own hue. Only the two end months are named:
 * six Persian month names across a sheet is a caption war, and the ends are the axis.
 */
function CategoryTrend({ trend, tone }: { trend: CategoryWindowTrend; tone: string }) {
  const top = Math.max(...trend.map(([, rial]) => rial), 1);
  return (
    <div class="rp-trend" aria-hidden="true">
      {trend.map(([month, rial], i) => (
        <div>
          <span style={{ height: `${Math.max(4, (80 * Math.fround(rial)) / top)}px`, background: rial > 0 ? tone : 'var(--surface-variant)' }} />
          <p>{i === 0 || i === trend.length - 1 ? MONTHS[month.month - 1] : ' '}</p>
        </div>
      ))}
    </div>
  );
}
type CategoryWindowTrend = ReturnType<typeof categoryWindow>['trend'];

/**
 * Which categories the report leaves out, chosen live: every tap recomputes the figures behind
 * the scrim, so the sheet needs no apply button. Every category is offered, قرض و همسر included —
 * they arrive pre-excluded on a fresh install, so the honest default survives with one mechanism.
 */
function ExcludeSheet() {
  const view = useLedger();
  const family = useFamily();
  const excluded = pref('reportExcluded');
  const offered = view.categories.filter((c) => !c.archived && c.kind !== 'transfer' && c.id !== CAT_UNCATEGORISED);
  return (
    <Sheet label="کدوم‌ها حساب نشن؟">
      <SheetTitle>کدوم‌ها حساب نشن؟</SheetTitle>
      <p class="rp-note rp-s">
        {'هر دسته‌ای که بزنی از جمع‌های دخل و خرج کنار گذاشته می‌شه — عددش جدا دیده ' +
          'می‌شه و توی دفتر سر جاش می‌مونه.' +
          (family.paired ? ' این انتخاب برای همهٔ خانواده‌ست و روی گوشی بقیه هم اعمال می‌شه.' : '')}
      </p>
      <div class="rp-flow rp-l">
        {offered.map((c) => {
          const chosen = excluded.includes(c.id);
          return (
            <FilterCategoryChip key={c.id} name={c.nameFa} chosen={chosen}
              onToggle={() => setReportExcluded(chosen ? excluded.filter((id) => id !== c.id) : [...excluded, c.id])} />
          );
        })}
      </div>
      <div class="rp-xl"><PillButton label="بستن" voice="primary" block onClick={closeSheet} /></div>
      {excluded.length > 0 && (
        <div class="rp-s">
          <PillButton label="هیچ‌کدوم حذف نشه" block onClick={() => { setReportExcluded([]); closeSheet(); }} />
        </div>
      )}
    </Sheet>
  );
}

registerTab('REPORT', { Component: ReportScreen });
registerSheet('reportCategory', ReportCategorySheet);
registerSheet('exclude', ExcludeSheet);
