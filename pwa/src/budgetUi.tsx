/**
 * آینده — BudgetUi.kt: the caps she keeps, the goals she is saving for, the installments she is
 * paying off, and her own verdict on her own spending. That is the whole reward system: no points,
 * no streaks, no confetti — a limit she chose, a figure she is working towards, and her answer to
 * «ارزش داشت؟».
 *
 * One screen, three bands, and a fourth — طلب و بدهی, whose one row is a door to its own page
 * (loansUi.tsx). Each section is one grouped object whose last row is the way to grow
 * it, under a heading in the assets tab's voice; the air between bands is the separation. Every
 * row opens its own sheet, and delete lives inside that sheet behind the two-tap confirm.
 *
 * Sheets are opened by id and resolve their row fresh on every render: the ledger can move under
 * an open sheet (a pasted message, a refile synced in), and a held snapshot would pin the sheet to
 * figures the screen behind it no longer shows. A row deleted elsewhere resolves to nothing and
 * the sheet simply closes.
 */
import { Fragment } from 'preact';
import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import {
  BUDGET_PERIODS, BUDGET_TOTAL_FA, BudgetLevel, BudgetPeriod, budgetConflicts, budgetDaysLeftFa, budgetNoteFa,
  budgetScopeFa, budgetScopeNoteFa, budgetTotalNoteFa,
} from './budget';
import type { BudgetProgress } from './budget';
import { chequeDateDigits, chequeTitleFa, chequeWarningFa, chequeWhenFa, parseChequeDate } from './cheques';
import { CategoryGrid } from './categoryGrid';
import { CategoryIcon, customGlyphs, glyphOf, hueCss } from './categoryIcon';
import type { CategoryGlyph } from './categoryIcon';
import { useLedger } from './derived';
import type { LedgerView } from './derived';
import { requestFamilySync, useFamily } from './family';
import { bidi, faCompact, faDate, faDay, faDigits, faNumber, faOrdinal, faWeekdayDate, parseAmount, today as tehranToday, tomanOf } from './format';
import { GOAL_HORIZONS, GoalHorizon, GoalKind, WORTH_IT_ANSWERS, goalNoteFa, goalWindowFa } from './goals';
import type { GoalProgress } from './goals';
import {
  MAX_INSTALLMENTS, firstInstallmentDue, installmentDueOn, installmentLeftFa, installmentLineFa, installmentNoteFa,
  installmentPaidBy, installmentSummary, rialToField, tomanFieldToRial,
} from './installments';
import type { InstallmentProgress, InstallmentSummary } from './installments';
import { jalaliMonthsAfter, jalaliOf, tehranDay } from './jalali';
import { LoansSection } from './loansUi';
import { DigitsField } from './manualTxn';
import type { Category, Goal, LedgerEntry, Txn } from './model';
import { closeSheet, openSheet, registerSheet, registerTab, showNotice } from './nav';
import {
  addBudget, addGoal, addInstallment, addInstallmentFrom, deleteGoal, editBudget, editGoal, keepBudget, readPlans,
  saveCheque, setChequePassed, setInstallmentPayment,
} from './plans';
import type { Plans } from './plans';
import { CAT_TRANSFER, categoryChoices } from './rules';
import { bankFa } from './sms';
import { pref } from './state';
import {
  AmountField, ChipChoice, Panel, PillButton, Screen, SegmentedChoice, Sheet, SheetDelete, SheetLabel, SheetTitle, TextField,
} from './ui';
import './budgetUi.css';

// ─────────────────────────── shared reads ───────────────────────────

/** The ledger and the plans read off it. The view is memoised per data version, household and Tehran day — all the plans read. */
function usePlans(): { view: LedgerView; plans: Plans } {
  const view = useLedger();
  const plans = useMemo(() => readPlans(view.allEntries, view.entries), [view]);
  return { view, plans };
}

/** Every edit the household can see asks for a sync, as the phone's view model does after each one. */
function synced(paired: boolean, action: () => void): () => void {
  return () => {
    action();
    if (paired) requestFamilySync(true);
  };
}

/**
 * A sheet's way out, once: a second closeSheet() before the first history pop lands would pop the
 * page underneath too. `then` runs after, as Kotlin's `close { … }` hides the sheet and then acts.
 */
function useClose(): (then?: () => void) => void {
  const done = useRef(false);
  return (then) => {
    if (!done.current) {
      done.current = true;
      closeSheet();
    }
    then?.();
  };
}

/** Closes the sheet when what it was opened on is gone — deleted here or on another phone. */
function useGone(gone: boolean, close: () => void): void {
  useEffect(() => { if (gone) close(); }, [gone]);
}

// ─────────────────────────── notifications ───────────────────────────

const noteListeners = new Set<() => void>();

/** Whether this browser will show a note. No API at all (Safari outside a home-screen app) is «no». */
export const canNotify = (): boolean => typeof Notification !== 'undefined' && Notification.permission === 'granted';

/**
 * The card's button. Denied is denied for good in a browser — there is no settings page to send her
 * to — so the card simply stays, as it does on the phone until the switch is on.
 */
export async function askNotify(): Promise<void> {
  if (typeof Notification === 'undefined') return;
  await Notification.requestPermission();
  for (const l of noteListeners) l();
}

/** [canNotify], re-rendering the caller when [askNotify] gets an answer. */
export function useCanNotify(): boolean {
  const [, tick] = useState(0);
  useEffect(() => {
    const l = () => tick((n) => n + 1);
    noteListeners.add(l);
    return () => { noteListeners.delete(l); };
  }, []);
  // ponytail: a permission changed in the browser's own settings shows on the next render; a
  // `permissions.query` onchange would make it live if that ever matters.
  return canNotify();
}

/**
 * Notifications are off and the screen has something it would otherwise have said out loud. Not an
 * error colour: nothing is broken, the app has been told to stay quiet. The card is shared and the
 * sentence is not — each screen names its own loss.
 */
export function NotifyBlockedCard({ what, onAsk = askNotify }: { what: string; onAsk?: () => void }) {
  return (
    <div class="plan-row notify-card" style={{ borderRadius: 'var(--r-group)' }}>
      <p class="plan-name">اعلان‌ها خاموشن</p>
      <p class="notify-what">{what}</p>
      {/* A browser with no notification API has nothing to ask; the sentence still stands. */}
      {typeof Notification !== 'undefined' && (
        <div class="mt-s"><PillButton voice="primary" label="روشن کردن اعلان" onClick={() => onAsk()} /></div>
      )}
    </div>
  );
}

// ─────────────────────────── the screen ───────────────────────────

/** Where a row sits in its band: the group's corners on the outer rows, square in between. */
function bandShape(index: number, count: number): string {
  const r = 'var(--r-group)';
  if (count === 1) return r;
  if (index === 0) return `${r} ${r} 0 0`;
  if (index === count - 1) return `0 0 ${r} ${r}`;
  return '0';
}

function BudgetScreen() {
  const { view, plans } = usePlans();
  const canNote = useCanNotify();
  const { budgets, goals, installments, cheques } = plans;
  const custom = customGlyphs(view.managedCategories);
  const days = pref('installmentReminder');
  const today = tehranToday();
  // Only raised where there is something to be quiet about — never the launch-time prompt.
  const notifyBlocked = (budgets.length > 0 ||
    (days >= 0 && (installments.some((p) => !p.done) || cheques.length > 0))) && !canNote;
  const caps = budgets.map((b) => b.goal);

  return (
    <Screen title="آینده">
      <SectionLabel first>بودجه‌ها</SectionLabel>
      {/* Above the cards: it is the reason they would otherwise be silent. */}
      {notifyBlocked && (
        <div class="mb-m">
          <NotifyBlockedCard what={budgets.length === 0
            // No budget means it was raised for the installment and cheque reminders alone.
            ? 'تا اعلان روشن نباشه، یادآوری سررسید قسط‌ها و چک‌ها بهت نمی‌رسه.'
            : 'بودجه‌هات همین‌جا حساب می‌شن، ولی تا اعلان روشن نباشه بیرون از برنامه خبری بهت نمی‌رسه.'} />
        </div>
      )}
      {budgets.length === 0 && (
        <p class="plan-empty">
          می‌تونی برای کل خرجت یه سقف بذاری، یا برای هر دسته جدا — هفتگی، ماهانه یا فصلی. وقتی به سقف نزدیک شدی یا از اون گذشتی، خبرت می‌کنیم.
        </p>
      )}
      {budgets.map((budget, i) => (
        <Fragment key={budget.goal.id}>
          {budgetConflicts(caps, budget.goal).length > 0 && (
            <p class="plan-dup">بودجهٔ تکراری؛ بازش کن و سقفی که می‌خوای بمونه رو انتخاب کن.</p>
          )}
          <BudgetCard budget={budget} radius={bandShape(i, budgets.length + 1)} custom={custom}
            onOpen={() => openSheet('budget', { id: budget.goal.id })} />
        </Fragment>
      ))}
      <AddRow label={budgets.length === 0 ? 'اولین بودجه' : 'بودجهٔ تازه'} radius={bandShape(budgets.length, budgets.length + 1)}
        onClick={() => openSheet('budget')} />

      <SectionLabel>هدف‌ها</SectionLabel>
      {goals.length === 0 && (
        <p class="plan-empty">اینجا فقط هدف‌هایی رو می‌بینی که خودت انتخاب کردی. خبری از امتیاز، روزهای پیاپی یا مقایسه با بقیه نیست.</p>
      )}
      {goals.map((progress, i) => (
        <GoalCard key={progress.goal.id} progress={progress} radius={bandShape(i, goals.length + 1)}
          onOpen={() => openSheet('goal', { id: progress.goal.id })} />
      ))}
      <AddRow label={goals.length === 0 ? 'اولین هدف' : 'هدف تازه'} radius={bandShape(goals.length, goals.length + 1)}
        onClick={() => openSheet('goal')} />

      <SectionLabel>قسط‌ها</SectionLabel>
      {installments.length === 0 && (
        // Says where the payments come from — the one thing nobody would guess: nothing here is typed twice.
        <p class="plan-empty">قسط گوشی، وام یا هر چیزی که ماه‌به‌ماه می‌دی. پرداختش که توی دفتر اومد، همین‌جا تیکش رو می‌زنی و می‌بینی چقدر مونده.</p>
      )}
      <InstallmentSummaryLines summary={installmentSummary(installments, tehranToday())} />
      {installments.map((progress, i) => (
        <InstallmentCard key={progress.plan.id} progress={progress} radius={bandShape(i, installments.length + 1)}
          onOpen={() => openSheet('installmentPayments', { id: progress.plan.id })} />
      ))}
      <AddRow label={installments.length === 0 ? 'اولین قسط' : 'قسط تازه'}
        radius={bandShape(installments.length, installments.length + 1)} onClick={() => openSheet('installment')} />

      <SectionLabel>چک‌ها</SectionLabel>
      {cheques.length === 0 && (
        // Says what the section watches for, since that is the reason to type a cheque in.
        <p class="plan-empty">چکی که کشیدی رو با تاریخش ثبت کن. نزدیک سررسید اگه موجودی حسابش کم باشه، همین‌جا می‌گیم چقدر کمه.</p>
      )}
      {cheques.map((cheque, i) => (
        <ChequeCard key={cheque.id} cheque={cheque} today={today} radius={bandShape(i, cheques.length + 1)}
          warning={chequeWarningFa(cheque, cheques, view.bankAccounts, today, days)}
          onOpen={() => openSheet('cheque', { id: cheque.id })} />
      ))}
      <AddRow label={cheques.length === 0 ? 'اولین چک' : 'چک تازه'} radius={bandShape(cheques.length, cheques.length + 1)}
        onClick={() => openSheet('cheque')} />

      <LoansSection />
    </Screen>
  );
}

/** The band title: one size and weight for all three, so the page reads as one document. */
function SectionLabel({ children, first }: { children: ComponentChildren; first?: boolean }) {
  return <h2 class={`plan-section${first ? ' first' : ''}`}>{children}</h2>;
}

/**
 * One row of a band, and the whole row is the way into its sheet — a row-sized target needs no
 * second button. A hairline under every card; the add row closes the band without one.
 */
function BandCard({ radius, onOpen, label, tone, children }: {
  radius: string; onOpen: () => void; label?: string; tone: string; children: ComponentChildren;
}) {
  return (
    <button type="button" class="plan-row divided" style={{ borderRadius: radius, '--tone': tone }} aria-label={label} onClick={onOpen}>
      {children}
    </button>
  );
}

/** The quiet way into each sheet: a well with a «+», leaving the loud shape to the ذخیره inside. */
function AddRow({ label, radius, onClick }: { label: string; radius: string; onClick: () => void }) {
  return (
    <button type="button" class="plan-row plan-add" style={{ borderRadius: radius }} onClick={onClick}>
      <span class="figure plan-plus" aria-hidden="true">+</span>
      <span>{label}</span>
    </button>
  );
}

/** «ویرایش» — the word for what tapping the card does, not a target of its own. */
function EditHint({ label = 'ویرایش' }: { label?: string }) {
  return <span class="plan-hint">{label}</span>;
}

/**
 * «۳۲ از ۵۰ میلیون» on one line, stepping down from 20px to 14px rather than wrapping: broken over
 * two lines it reads as two amounts instead of one out of the other. The step is an estimate from
 * its length against the row's width, the same one TomanFigure makes.
 */
function FitFigure({ text }: { text: string }) {
  const chars = [...text.replace(/[⁦-⁩]/g, '')].length;
  return (
    <span class="plan-fit">
      <span class="figure plan-figure" style={{ '--fit': Math.max(1, chars * FIT_EM_PER_CHAR) }}>{text}</span>
    </span>
  );
}
/** Modam at the figure width averages about .43em a character, digits and words alike; a little over it steps down before it clips. */
const FIT_EM_PER_CHAR = 0.45;

/** The bar cannot overflow: being over is said in words and colour, never drawn past the track. */
function Bar({ share }: { share: number }) {
  return <span class="plan-bar" aria-hidden="true"><span style={{ width: `${share * 100}%` }} /></span>;
}

/** The mark a total wears: a ring drawn full — the whole of something, and not one of the rooms. */
function TotalMark({ size = 20 }: { size?: number }) {
  const r = size * 0.42;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true" focusable="false" style={{ color: 'var(--mark)', display: 'block' }}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="currentColor" stroke-width={1.6} />
      <circle cx={size / 2} cy={size / 2} r={r * 0.5} fill="currentColor" />
    </svg>
  );
}

/** The category's own mark on its disc, as the picker draws it; the total's ring in `primary`. */
function BudgetDisc({ budget, custom }: { budget: BudgetProgress; custom: Record<string, CategoryGlyph> }) {
  const glyph = glyphOf(budget.categoryFa, custom);
  return (
    <span class="disc plan-disc" style={{ '--mark': budget.total ? 'var(--primary)' : hueCss(glyph) }}>
      {budget.total ? <TotalMark /> : <CategoryIcon glyph={glyph} size={20} color="var(--mark)" />}
    </span>
  );
}

/** How the four levels speak: `error` only when over, amber for both thresholds, else `primary`. */
function budgetTone(level: number): string {
  if (level === BudgetLevel.OVER) return 'var(--error)';
  if (level === BudgetLevel.NEAR || level === BudgetLevel.CLOSE) return 'var(--secondary)';
  return 'var(--primary)';
}

/**
 * One cap: which category, how much of it is gone, how long is left, and one sentence when there is
 * something to say. The percent is not clamped — «۱۳۰٪» is exactly the number she needs.
 */
function BudgetCard({ budget, radius, custom, onOpen }: {
  budget: BudgetProgress; radius: string; custom: Record<string, CategoryGlyph>; onOpen: () => void;
}) {
  const note = budgetNoteFa(budget);
  // «ماهانه • مرداد»: the shape and the window it is measuring; a third part only for whose it is.
  const subtitle = [budget.period.everyFa, budget.window.fa, budgetScopeFa(budget)].filter((s) => s !== '').join(' • ');
  const spent = faCompact(tomanOf(budget.spentRial));
  const cap = faCompact(tomanOf(budget.capRial));
  const label = `${budget.categoryFa}، ${subtitle}: ${spent} از ${cap} تومان، ${faNumber(budget.percent)} درصد. ` +
    `${budgetDaysLeftFa(budget)}. ${note}`;
  return (
    <BandCard radius={radius} onOpen={onOpen} label={label} tone={budgetTone(budget.level)}>
      <span class="plan-head">
        <BudgetDisc budget={budget} custom={custom} />
        <span class="grow">
          <span class="plan-name">{budget.categoryFa}</span>
          <span class="plan-sub">{subtitle}</span>
        </span>
        <EditHint />
      </span>
      <span class="plan-figure-row mt-m">
        <FitFigure text={bidi(`${spent} از ${cap}`)} />
        <span class="figure plan-percent">{faNumber(budget.percent)}٪</span>
      </span>
      <span class="mt-s"><Bar share={budget.share} /></span>
      <span class="plan-sub mt-s">{budgetDaysLeftFa(budget)}</span>
      {note !== '' && <span class={`plan-note mt-xs${budget.over ? ' loud' : ''}`}>{note}</span>}
      {budget.partWindow && (
        // The window is the whole month either way — anything else would disagree with دخل و خرج.
        <span class="plan-part mt-xs">از اول {budget.window.fa} حساب شده، نه از روزی که بودجه رو گذاشتی.</span>
      )}
    </BandCard>
  );
}

function GoalCard({ progress, radius, onOpen }: { progress: GoalProgress; radius: string; onOpen: () => void }) {
  const tone = progress.done ? 'var(--tertiary)' : 'var(--primary)';
  // Blank for the reason a cap's subtitle is: a goal says whose it is only when that distinguishes it.
  const scope = progress.ownerName.trim() ? `خانوادگی • ${progress.ownerName}` : progress.shared ? 'خانوادگی' : '';
  const note = goalNoteFa(progress);
  return (
    <BandCard radius={radius} onOpen={onOpen} tone={tone}>
      <span class="plan-head">
        <span class="grow">
          <span class="plan-name">{progress.goal.nameFa}</span>
          {scope !== '' && <span class="plan-sub">{scope}</span>}
        </span>
        <EditHint />
      </span>
      <span class="plan-figure-row mt-s">
        <FitFigure text={bidi(`${faCompact(tomanOf(progress.currentRial))} از ${faCompact(tomanOf(progress.targetRial))}`)} />
      </span>
      <span class="mt-s"><Bar share={progress.share} /></span>
      {/* What progress is counted from, always — or a goal set on the 28th reads as a made-up number. */}
      <span class="plan-sub mt-s">{goalWindowFa(progress)}</span>
      {note && <span class={`plan-note quiet mt-xs${note[1] ? ' loud' : ''}`}>{note[0]}</span>}
    </BandCard>
  );
}

/** A goal card read as a plan. It opens on «پرداخت‌ها»: saying which transaction paid it is why she comes back. */
function InstallmentCard({ progress, radius, onOpen }: { progress: InstallmentProgress; radius: string; onOpen: () => void }) {
  const tone = progress.done ? 'var(--tertiary)' : progress.overdueRial > 0 ? 'var(--error)' : 'var(--primary)';
  const note = installmentNoteFa(progress);
  const left = installmentLeftFa(progress);
  return (
    <BandCard radius={radius} onOpen={onOpen} tone={tone}>
      <span class="plan-head">
        <span class="grow">
          <span class="plan-name">{progress.plan.nameFa}</span>
          <span class="plan-sub">ماهی {faCompact(tomanOf(progress.plan.targetRial))} تومان</span>
        </span>
        <EditHint label="پرداخت‌ها" />
      </span>
      <span class="plan-figure-row mt-s">
        <FitFigure text={bidi(`${faCompact(tomanOf(progress.paidRial))} از ${faCompact(tomanOf(progress.totalRial))}`)} />
      </span>
      <span class="mt-s"><Bar share={progress.share} /></span>
      {left && <span class="plan-sub mt-s">{left}</span>}
      {note && <span class={`plan-note quiet mt-xs${note[1] ? ' loud' : ''}`}>{note[0]}</span>}
    </BandCard>
  );
}

/**
 * One cheque (ChequeCard, BudgetUi.kt): who for, from which account, on which day and how far off,
 * and — inside the reminder's window — whether the account holds it, in words; amber confirms, red
 * once the date has gone by. «پاس شد» sits beside the row's own button, since a button cannot hold
 * one, and a stray tap is taken back from the notice it leaves.
 */
function ChequeCard({ cheque, warning, today, radius, onOpen }: {
  cheque: Goal; warning: string | null; today: number; radius: string; onOpen: () => void;
}) {
  const tone = cheque.startsOn < today ? 'var(--error)' : 'var(--secondary)';
  const pass = () => {
    setChequePassed(cheque.id, true);
    showNotice('چک پاس شد', { label: 'برگردون', run: () => setChequePassed(cheque.id, false) });
  };
  return (
    <div class="plan-row divided" style={{ borderRadius: radius, '--tone': tone }}>
      <button type="button" class="cheque-open" onClick={onOpen}>
        <span class="plan-head">
          <span class="grow">
            <span class="plan-name">{chequeTitleFa(cheque)}</span>
            <span class="plan-sub">از {bankFa(cheque.categoryId ?? '')} • {faDate(cheque.startsOn)}</span>
          </span>
          <EditHint />
        </span>
        <span class="plan-figure-row mt-s">
          <FitFigure text={bidi(`${faCompact(tomanOf(cheque.targetRial), 3)} تومان`)} />
          <span class="cheque-when">{chequeWhenFa(cheque.startsOn, today)}</span>
        </span>
        {warning && <span class="plan-note loud mt-xs">{warning}</span>}
      </button>
      <div class="mt-s"><PillButton label="پاس شد" onClick={pass} /></div>
    </div>
  );
}

/**
 * Every plan at once, above their cards: what leaves each month, what is left and until when, and how
 * this month's payments stand. Words on the page, not a card — a card here would read as one more plan.
 */
function InstallmentSummaryLines({ summary }: { summary: InstallmentSummary | null }) {
  if (!summary) return null;
  return (
    <div class="inst-summary">
      <FitFigure text={bidi(`ماهی ${faCompact(tomanOf(summary.monthlyRial))} تومان قسط`)} />
      <p class="plan-lede">{faCompact(tomanOf(summary.leftRial))} تومان مونده، تا {faDate(summary.lastDue)}</p>
      {summary.dueThisMonth > 0 && (
        <p class="plan-lede">این ماه {faNumber(summary.paidThisMonth)} از {faNumber(summary.dueThisMonth)} قسط پرداخت شده.</p>
      )}
    </div>
  );
}

// ─────────────────────────── the sheets' shared parts ───────────────────────────

/** A line under a control, in the sheet's small voice. */
function SheetNote({ children, live }: { children: ComponentChildren; live?: boolean }) {
  return <p class="plan-sheet-note" aria-live={live ? 'polite' : undefined}>{children}</p>;
}

/** «کل خرج» as a thing to pick — the roof, offered above the rooms rather than as a seventeenth tile. */
function TotalChoice({ selected, onClick }: { selected: boolean; onClick: () => void }) {
  return (
    <button type="button" class={`total-choice${selected ? ' selected' : ''}`} onClick={onClick}
      aria-label={BUDGET_TOTAL_FA + (selected ? '، انتخاب‌شده' : '')}>
      <span class="disc plan-disc"><TotalMark /></span>
      <span class="grow">
        <span class="plan-name">{BUDGET_TOTAL_FA}</span>
        <span class="total-sub">سقف روی همهٔ خرج‌ها با هم</span>
      </span>
    </button>
  );
}

/**
 * «مال کیه؟» — who sees a figure and whose spending counts. The effect is invisible, so the only way
 * she knows what she chose is the sentence under it, announced when it changes. Only drawn in a
 * household: «مال خودم» with nobody to share with is not a choice.
 */
function WhoseChoice({ shared, wasShared, onChange }: { shared: boolean; wasShared: boolean; onChange: (v: boolean) => void }) {
  return (
    <>
      <SheetLabel>مال کیه؟</SheetLabel>
      <SegmentedChoice options={[false, true]} selected={shared} label={(it) => (it ? 'خانوادگی' : 'مال خودم')} onSelect={onChange} />
      <SheetNote live>{budgetScopeNoteFa(shared, wasShared)}</SheetNote>
    </>
  );
}

const UNREADABLE = 'این عدد قابل خوندن نیست. فقط عدد وارد کن.';

/** A whole number within [lo, hi] in whatever digits she typed, or null. */
function wholeIn(text: string, lo: number, hi: number): number | null {
  const n = parseAmount(text);
  return n != null && Number.isInteger(n) && n >= lo && n <= hi ? n : null;
}

// ─────────────────────────── a budget ───────────────────────────

/**
 * A budget in three answers — which category, how long, how much — in that order: the category has
 * no sensible default, and a cap is a figure picked *for* something. No name field: its name is its
 * category. Editing is the same sheet with the category shown rather than asked.
 */
function BudgetSheet({ id }: { id?: string }) {
  const { view, plans } = usePlans();
  const paired = useFamily().paired;
  const close = useClose();
  const editing = id != null ? plans.budgets.find((b) => b.goal.id === id) ?? null : null;
  useGone(id != null && editing == null, close);

  const [picked, setPicked] = useState<Category | null>(null);
  // «کل خرج» is a choice about *what*, not a category; picking either clears the other.
  const [wantsTotal, setWantsTotal] = useState(false);
  // A figure starts out hers and becomes the household's by her saying so.
  const [shared, setShared] = useState(editing?.shared ?? false);
  // A month: what her salary, her rent and every bill already run on.
  const [period, setPeriod] = useState<BudgetPeriod>(editing?.period ?? BudgetPeriod.MONTH);
  const [amount, setAmount] = useState(editing ? rialToField(editing.capRial) : '');
  // Raised by a save tap that could not save: from then on every blank answer says so.
  const [tried, setTried] = useState(false);
  if (id != null && editing == null) return null;

  const capRial = tomanFieldToRial(amount);
  const candidate: Goal = {
    id: editing?.goal.id ?? '', nameFa: '', targetRial: capRial ?? 0, kind: GoalKind.CAP,
    categoryId: editing ? editing.goal.categoryId : picked?.id ?? null, period: period.id, startsOn: 0, endsOn: null,
    createdAt: 0, updatedAt: 0, deleted: false, shared, ownerMemberId: '', editedByMemberId: '',
  };
  const conflicts = budgetConflicts(plans.budgets.map((b) => b.goal), candidate);
  const chosen = editing != null || wantsTotal || picked != null;
  const spending = categoryChoices(view.categories, 'out');
  // Only the spending side, in the grid's order: those are the only ones a roof could have counted.
  const excludedFa = spending.filter((c) => pref('reportExcluded').includes(c.id)).map((c) => c.nameFa);
  const title = editing ? 'ویرایش بودجه' : 'بودجهٔ تازه';

  const save = () => {
    // Never dimmed — a dead button explains nothing — so a tap that cannot save raises the words under
    // whatever is missing. Focus lets go first: the keyboard was covering them. A clash is already said.
    (document.activeElement as HTMLElement | null)?.blur();
    setTried(true);
    if (capRial == null || !chosen || conflicts.length > 0) return;
    if (editing) return close(synced(paired, () => editBudget(editing.goal.id, period, capRial, shared)));
    close(synced(paired, () => addBudget(wantsTotal ? null : picked, period, capRial, shared)));
  };

  return (
    <Sheet onClose={() => close()} label={title}>
      <SheetTitle>{title}</SheetTitle>
      {editing ? (
        <>
          {/* Which budget this is, said the way its card says it — not a field: another category is another budget. */}
          <div class="row-flex plan-editing">
            <BudgetDisc budget={editing} custom={customGlyphs(view.managedCategories)} />
            <span class="plan-name">{editing.categoryFa}</span>
          </div>
          <SheetNote>{editing.total ? budgetTotalNoteFa(excludedFa) : 'دسته‌اش عوض نمی‌شه — برای دستهٔ دیگه، بودجهٔ تازه بساز.'}</SheetNote>
        </>
      ) : (
        <>
          <SheetLabel>روی چی؟</SheetLabel>
          <TotalChoice selected={wantsTotal} onClick={() => {
            setWantsTotal(!wantsTotal);
            if (!wantsTotal) setPicked(null);
          }} />
          {/* What a roof leaves out, while she is deciding the number rather than afterwards. */}
          {wantsTotal && <SheetNote>{budgetTotalNoteFa(excludedFa)}</SheetNote>}
          <div class="mt-m">
            <CategoryGrid categories={spending.filter((c) => c.id !== CAT_TRANSFER)} selected={picked?.id ?? null}
              onSelect={(c) => { setPicked(c); setWantsTotal(false); }} selectedLabel="انتخاب‌شده" />
          </div>
          {/* The grid cannot flag itself the way a field does, so a refused save's words live under it. */}
          {tried && !chosen && <p class="grid-error" role="status">کل خرج یا یه دسته رو انتخاب کن.</p>}
        </>
      )}

      <SheetLabel>هر چه مدت؟</SheetLabel>
      <SegmentedChoice options={[...BUDGET_PERIODS]} selected={period} label={(p) => p.everyFa} onSelect={setPeriod} />

      <SheetLabel>سقف خرج، به تومان</SheetLabel>
      <AmountField label="مثلاً ۵ میلیون" ariaLabel="سقف خرج به تومان" raw={amount} onRaw={setAmount} decimals={1}
        error={amount.trim() === '' ? (tried ? 'سقفش رو بنویس.' : null) : capRial == null ? UNREADABLE : null} />

      {paired && <WhoseChoice shared={shared} wasShared={editing?.shared === true} onChange={setShared} />}

      {chosen && conflicts.length > 0 && (
        <>
          <p class="plan-conflict" role="status">
            {'برای همین دسته، دوره و افراد، بودجهٔ دیگه‌ای هست: ' +
              conflicts.map((g) => faCompact(tomanOf(g.targetRial))).join('، ') +
              ' تومان. برای تغییر سقف، همون بودجه رو ویرایش کن.'}
          </p>
          {editing && period === editing.period && shared === editing.shared && (
            // Error ink: the way out of the clash deletes the other budgets. A block: the label is a sentence.
            <div class="mt-m">
              <PillButton voice="danger" block label={`فقط سقف ${faCompact(tomanOf(editing.capRial))} بمونه؛ بودجه‌های تکراری حذف بشن`}
                onClick={() => close(synced(paired, () => keepBudget(editing.goal.id)))} />
            </div>
          )}
        </>
      )}
      <div class="sheet-actions">
        <PillButton voice="primary" block label={editing ? 'ذخیره تغییرات' : 'ذخیره بودجه'} onClick={save} />
      </div>
      {editing && <SheetDelete label="حذف این بودجه" onConfirmed={() => close(synced(paired, () => deleteGoal(editing.goal.id)))} />}
    </Sheet>
  );
}

// ─────────────────────────── a goal ───────────────────────────

/**
 * A goal in three answers: what for, how much, by when. «تا کِی» is four pills, not a date picker.
 * Editing arrives with «تا کِی؟» filled with nothing: the pills measure from today, so re-selecting
 * «۶ ماه» on an old goal would quietly move its deadline. Left alone, the deadline she has stands.
 */
function GoalSheet({ id }: { id?: string }) {
  const { plans } = usePlans();
  const paired = useFamily().paired;
  const close = useClose();
  const editing = id != null ? plans.goals.find((g) => g.goal.id === id) ?? null : null;
  useGone(id != null && editing == null, close);

  const [shared, setShared] = useState(editing?.shared ?? false);
  const [name, setName] = useState(editing?.goal.nameFa ?? '');
  const [amount, setAmount] = useState(editing ? rialToField(editing.targetRial) : '');
  const [horizon, setHorizon] = useState<GoalHorizon | null>(editing ? null : GoalHorizon.HALF);
  // Raised by a save tap that could not save, as in BudgetSheet.
  const [tried, setTried] = useState(false);
  if (id != null && editing == null) return null;

  const targetRial = tomanFieldToRial(amount);
  // Written out, so «۶ ماه» is never a length to convert in her head — and while editing with the
  // pills untouched, the deadline she already has.
  const deadline = horizon != null ? horizon.endsOn(tehranDay(Date.now())) : editing?.goal.endsOn ?? null;
  const title = editing ? 'ویرایش هدف' : 'هدف تازه';
  const save = () => {
    // Never dimmed: a tap that cannot save says why, as BudgetSheet's does.
    (document.activeElement as HTMLElement | null)?.blur();
    setTried(true);
    if (targetRial == null || name.trim() === '') return;
    if (editing) close(synced(paired, () => editGoal(editing.goal.id, name.trim(), targetRial, horizon, shared)));
    else close(synced(paired, () => addGoal(name.trim(), targetRial, horizon ?? GoalHorizon.HALF, shared)));
  };

  return (
    <Sheet onClose={() => close()} label={title}>
      <SheetTitle>{title}</SheetTitle>
      <SheetLabel>هدفت چیه؟</SheetLabel>
      <TextField label="مثلاً سفر، یا پیش‌پرداخت خونه" value={name} onInput={(v) => setName(v.slice(0, 40))} maxLength={40}
        error={tried && name.trim() === '' ? 'اسمش رو بنویس.' : null} />
      <SheetLabel>چقدر، به تومان</SheetLabel>
      <AmountField label="مبلغ هدف" ariaLabel="مبلغ هدف به تومان" raw={amount} onRaw={setAmount} decimals={1}
        error={amount.trim() === '' ? (tried ? 'مبلغش رو بنویس.' : null) : targetRial == null ? UNREADABLE : null} />
      <SheetLabel>تا کِی؟</SheetLabel>
      <SegmentedChoice<GoalHorizon | null> options={[...GOAL_HORIZONS]} selected={horizon} label={(h) => h?.fa ?? ''}
        onSelect={setHorizon} fontSize={14} />
      <SheetNote>{deadline == null ? 'بدون مهلت، هر وقت رسید.' : `مهلت: ${faDate(deadline)}`}</SheetNote>

      {paired && <WhoseChoice shared={shared} wasShared={editing?.shared === true} onChange={setShared} />}

      <div class="sheet-actions">
        <PillButton voice="primary" block label={editing ? 'ذخیره تغییرات' : 'ذخیره هدف'} onClick={save} />
      </div>
      {editing && <SheetDelete label="حذف این هدف" onConfirmed={() => close(synced(paired, () => deleteGoal(editing.goal.id)))} />}
    </Sheet>
  );
}

// ─────────────────────────── a cheque ───────────────────────────

/**
 * 'cheque' {id?} (ChequeSheet, BudgetUi.kt): how much, the date on it, the account it draws on, and —
 * if she likes — who or what it is for. The date is typed as printed and written out under the field
 * before she saves; the accounts are the bank accounts sheet's, whose balance the warning reads.
 * Editing is the same sheet filled in, with delete under it.
 */
function ChequeSheet({ id }: { id?: string }) {
  const { view, plans } = usePlans();
  const close = useClose();
  const editing = id != null ? plans.cheques.find((c) => c.id === id) ?? null : null;
  useGone(id != null && editing == null, close);
  const accounts = view.bankAccounts;
  const [amount, setAmount] = useState(editing ? rialToField(editing.targetRial) : '');
  const [dateDigits, setDateDigits] = useState(editing ? chequeDateDigits(editing.startsOn) : '');
  // The only account there is, when there is only one.
  const [bank, setBank] = useState(editing?.categoryId ?? (accounts.length === 1 ? accounts[0].bank : ''));
  const [name, setName] = useState(editing?.nameFa ?? '');
  // Raised by a save tap that could not save, as in BudgetSheet.
  const [tried, setTried] = useState(false);
  if (id != null && editing == null) return null;

  const amountRial = tomanFieldToRial(amount);
  const due = parseChequeDate(dateDigits);
  // An account forgotten since the cheque was written stays offered, or editing would lose it.
  const banks = [...new Set([...accounts.map((a) => a.bank), ...(editing?.categoryId ? [editing.categoryId] : [])])];
  const title = editing ? 'ویرایش چک' : 'چک تازه';
  const save = () => {
    // Never dimmed: a tap that cannot save says why, as BudgetSheet's does.
    (document.activeElement as HTMLElement | null)?.blur();
    setTried(true);
    if (amountRial == null || due == null || bank === '') return;
    close(() => saveCheque(editing?.id ?? null, name.trim(), amountRial, due, bank));
  };

  return (
    <Sheet onClose={() => close()} label={title}>
      <SheetTitle>{title}</SheetTitle>
      <SheetLabel>مبلغ چک، به تومان</SheetLabel>
      <AmountField label="مبلغ چک" ariaLabel="مبلغ چک به تومان" raw={amount} onRaw={setAmount} decimals={1}
        error={amount.trim() === '' ? (tried ? 'مبلغش رو بنویس.' : null) : amountRial == null ? UNREADABLE : null} />
      <SheetLabel>تاریخ چک</SheetLabel>
      <DigitsField digits={dateDigits} onDigits={setDateDigits} length={8} sep="/" at={[4, 6]} label="مثلاً ۱۴۰۵/۰۹/۱۵"
        ariaLabel="تاریخ چک" hint={due == null && (tried || dateDigits !== '') ? 'تاریخ رو هشت‌رقمی بنویس، مثل ۱۴۰۵/۰۹/۱۵.' : null} />
      {/* The date written out, so a slipped digit is caught before it is saved. */}
      {due != null && <SheetNote>{faWeekdayDate(due)}</SheetNote>}
      <SheetLabel>از کدوم حساب؟</SheetLabel>
      {banks.length === 0
        ? <p class="plan-lede">هنوز حساب بانکی‌ای نداریم؛ با اولین پیامک بانک، حسابش اینجا میاد.</p>
        : <ChipChoice options={banks} selected={bank} label={bankFa} onSelect={setBank} />}
      {tried && bank === '' && <p class="field-support error" role="status">حسابش رو انتخاب کن.</p>}
      <SheetLabel>برای کی یا چی؟</SheetLabel>
      <TextField label="اختیاری؛ مثلاً اجاره" value={name} onInput={(v) => setName(v.slice(0, 40))} maxLength={40} />
      <div class="sheet-actions">
        <PillButton voice="primary" block label={editing ? 'ذخیره تغییرات' : 'ذخیره چک'} onClick={save} />
      </div>
      {editing && <SheetDelete label="حذف این چک" onConfirmed={() => close(() => deleteGoal(editing.id))} />}
    </Sheet>
  );
}

// ─────────────────────────── an installment ───────────────────────────

/**
 * A plan in four answers: what for, how much each month, how many are left, and on which day. The
 * two dates they imply are written out before she saves. [fromPayment] is a payment she has just
 * filed: its amount, its day, and the day of the month is not asked — it is the day money left.
 */
function InstallmentSheet({ fromPayment, onSave, onDismiss }: {
  fromPayment: LedgerEntry | null;
  onSave: (name: string, paymentRial: number, count: number, dayOfMonth: number) => void;
  onDismiss: () => void;
}) {
  const [today] = useState(() => tehranDay(Date.now()));
  const firstDue = fromPayment?.txn.day ?? null;
  const paid = fromPayment?.txn.amountRial;
  const [name, setName] = useState('');
  const [amount, setAmount] = useState(paid != null ? rialToField(paid) : '');
  const [countText, setCountText] = useState('');
  // Today's day: the likeliest moment to add a plan is the day a payment has just gone.
  const [dayText, setDayText] = useState(String(jalaliOf(firstDue ?? today).day));
  const paymentRial = tomanFieldToRial(amount);
  const count = wholeIn(countText, 1, MAX_INSTALLMENTS);
  const dayOfMonth = wholeIn(dayText, 1, 31);
  // Raised by a save tap that could not save, as in BudgetSheet.
  const [tried, setTried] = useState(false);
  const countBad = count == null && (tried || countText.trim() !== '');
  const first = firstDue ?? (dayOfMonth != null ? firstInstallmentDue(dayOfMonth, today) : null);
  const save = () => {
    // Never dimmed: a tap that cannot save says why, as BudgetSheet's does.
    (document.activeElement as HTMLElement | null)?.blur();
    setTried(true);
    if (name.trim() === '' || paymentRial == null || count == null || dayOfMonth == null) return;
    onSave(name.trim(), paymentRial, count, dayOfMonth);
  };

  return (
    <Sheet onClose={onDismiss} label="قسط تازه">
      <SheetTitle>قسط تازه</SheetTitle>
      <SheetLabel>قسطِ چی؟</SheetLabel>
      <TextField label="مثلاً گوشی، یا وام خونه" value={name} onInput={(v) => setName(v.slice(0, 40))} maxLength={40}
        error={tried && name.trim() === '' ? 'اسمش رو بنویس.' : null} />
      <SheetLabel>هر قسط چقدره، به تومان</SheetLabel>
      <AmountField label="مبلغ هر قسط" ariaLabel="مبلغ هر قسط به تومان" raw={amount} onRaw={setAmount} decimals={1}
        error={amount.trim() === '' ? (tried ? 'مبلغش رو بنویس.' : null) : paymentRial == null ? UNREADABLE : null} />
      <SheetLabel>{firstDue != null ? 'چند قسط، با همین یکی؟' : 'چند قسط مونده؟'}</SheetLabel>
      {/* The range names the fix for a blank count as well as a wrong one. */}
      <TextField value={faDigits(countText)} onInput={(v) => setCountText(v.slice(0, 4))} inputMode="numeric"
        error={countBad ? `یه عدد بین ۱ و ${faNumber(MAX_INSTALLMENTS)} بنویس.` : null}
        support={firstDue != null ? 'همین پرداخت می‌شه قسط اول؛ قسط‌های قبلش رو نشمار.'
          : 'اگه چندتاش رو قبلاً دادی، فقط باقی‌مونده‌ها رو بشمار.'} />
      {firstDue == null && (
        <>
          <SheetLabel>چندمِ هر ماه سررسیده؟</SheetLabel>
          {/* Its words are the line under it, «یه روز بین ۱ و ۳۱.», whenever the day is not one. */}
          <div class={dayOfMonth == null && (tried || dayText.trim() !== '') ? 'field-invalid' : ''}>
            <TextField value={faDigits(dayText)} onInput={(v) => setDayText(v.slice(0, 2))} inputMode="numeric" />
          </div>
        </>
      )}
      {/* The two dates the answers imply, so a wrong count or day is caught before it is saved. Announced
          only while it is the day's refusal: the dates change with every digit of the count. */}
      <SheetNote live={first == null}>
        {first == null ? 'یه روز بین ۱ و ۳۱.'
          : count == null ? `اولین سررسید: ${faDate(first)}`
          : `اولین سررسید ${faDate(first)}، آخری ${faDate(jalaliMonthsAfter(first, count - 1))}.`}
      </SheetNote>
      <div class="sheet-actions">
        <PillButton voice="primary" block label="ذخیره قسط" onClick={save} />
      </div>
    </Sheet>
  );
}

/** 'installment' {fromRef?}: a new plan — from the tab, or made from a payment she has just filed. */
function InstallmentRoute({ fromRef }: { fromRef?: string }) {
  const { view } = usePlans();
  const close = useClose();
  const entry = fromRef != null ? view.allEntries.find((e) => e.txn.ref === fromRef) ?? null : null;
  useGone(fromRef != null && entry == null, close);
  if (fromRef != null && entry == null) return null;
  return (
    <InstallmentSheet fromPayment={entry} onDismiss={() => close()} onSave={(name, payment, count, day) => close(() => {
      if (entry) addInstallmentFrom(entry, name, payment, count);
      else addInstallment(name, payment, count, day);
    })} />
  );
}

/**
 * Which transactions paid this plan — the whole of what editing a plan means, plus delete. Two lists
 * of one row: linked and ticked, then the ones that could be a payment, its exact amount first. Cash
 * is entered in دفتر first like anything else, and then turns up here — no figure typed twice.
 */
function InstallmentPaymentsSheet({ id }: { id: string }) {
  const { plans } = usePlans();
  const paired = useFamily().paired;
  const close = useClose();
  const progress = plans.installments.find((p) => p.plan.id === id) ?? null;
  useGone(progress == null, close);
  if (!progress) return null;
  const { plan } = progress;
  const change = (entry: LedgerEntry, paid: boolean) => setInstallmentPayment(entry, paid ? plan.id : null);
  return (
    <Sheet onClose={() => close()} label={plan.nameFa}>
      <SheetTitle>{plan.nameFa}</SheetTitle>
      <p class="plan-lede">
        ماهی {faCompact(tomanOf(plan.targetRial))} تومان، از {faDate(plan.startsOn)} تا {faDate(plan.endsOn ?? plan.startsOn)}
      </p>
      <InstallmentSchedule progress={progress} />
      {(progress.payments.length > 0 || progress.olderRial > 0) && (
        <>
          <SheetLabel>پرداخت‌هایی که وصل کردی</SheetLabel>
          {progress.payments.map((entry) => <PaymentRow key={entry.txn.ref} entry={entry} paid onChange={(v) => change(entry, v)} />)}
          {progress.olderRial > 0 && (
            <p class="plan-older">{faCompact(tomanOf(progress.olderRial))} تومان هم از پرداخت‌های قدیمی‌تری حساب شده که دیگه توی دفتر نیستن.</p>
          )}
        </>
      )}
      {!progress.done && (
        <>
          <SheetLabel>کدوم تراکنش پرداخت این قسط بود؟</SheetLabel>
          {progress.candidates.length === 0
            ? <p class="plan-lede">تراکنشی پیدا نشد که بخوره. اگه نقدی دادی، اول توی دفتر دستی ثبتش کن.</p>
            : progress.candidates.map((entry) => <PaymentRow key={entry.txn.ref} entry={entry} paid={false} onChange={(v) => change(entry, v)} />)}
        </>
      )}
      <div class="mt-l">
        <SheetDelete label="حذف این قسط" onConfirmed={() => close(synced(paired, () => deleteGoal(plan.id)))} />
      </div>
    </Sheet>
  );
}

/** How many open payments the schedule lists before folding the rest into a line — half a year. */
const SCHEDULE_ROWS = 6;

/**
 * The plan month by month: the payments covered folded into one line, then the ones still open, each
 * on its day, with a late one said in words. Covered counts from the first, as paidCount does.
 */
function InstallmentSchedule({ progress }: { progress: InstallmentProgress }) {
  const today = tehranToday();
  const paid = progress.paidCount;
  const covered = progress.done ? 'همه‌ی قسط‌ها پرداخت شد.'
    : paid === 1 ? 'قسط اول پرداخت شد.'
    : paid > 1 ? `${faNumber(paid)} قسط اول پرداخت شد.`
    : null;
  const open = Array.from({ length: Math.min(progress.count - paid, SCHEDULE_ROWS) }, (_, k) => paid + k);
  const rest = progress.count - paid - SCHEDULE_ROWS;
  return (
    <>
      <SheetLabel>جدول قسط‌ها</SheetLabel>
      {covered && <p class="plan-lede">{covered}</p>}
      {open.map((i) => {
        const due = installmentDueOn(progress.plan, i);
        const state = due < today ? 'late' : due === today ? 'today' : null;
        return (
          <div key={i} class="due-row">
            <span class="grow plan-title">قسط {faOrdinal(i + 1)}</span>
            <span class="plan-lede">{faDate(due)}</span>
            {state && <span class={`due-state ${state}`}>{state === 'late' ? 'گذشته' : 'امروز'}</span>}
          </div>
        );
      })}
      {rest > 0 && <p class="plan-lede">و {faNumber(rest)} قسط دیگه، تا {faDate(progress.plan.endsOn ?? progress.plan.startsOn)}.</p>}
    </>
  );
}

/** One transaction, ticked when it is a payment of the plan. The whole row is the checkbox. */
function PaymentRow({ entry, paid, onChange }: { entry: LedgerEntry; paid: boolean; onChange: (paid: boolean) => void }) {
  return (
    <label class="pay-row">
      <input type="checkbox" checked={paid} onChange={(e) => onChange(e.currentTarget.checked)} />
      <span class="grow">
        <span class="plan-title ellipsis">{entry.txn.merchant.trim() ? entry.txn.merchant : entry.categoryFa}</span>
        <span class="plan-sub">{faDay(entry.txn.day)}</span>
      </span>
      {entry.txn.amountRial != null && <span class="figure pay-amount">{bidi(`${faCompact(tomanOf(entry.txn.amountRial))} تومان`)}</span>}
    </label>
  );
}

// ─────────────────────────── from a payment to its plan ───────────────────────────

/** What one transaction is called on its own: the merchant, or the bank that reported it (Timeline.kt txnTitleFa). */
function txnTitleFa(txn: Txn): string {
  return txn.merchant.trim() ? txn.merchant : txn.sourceKind === 'manual' ? 'مورد دستی' : bankFa(txn.bank);
}

/**
 * Opens the link sheet for one transaction. The prop is `txnRef`, not `ref`: Preact takes `ref` out
 * of a component's props and would try to write to the string.
 */
export function openInstallmentLink(ref: string): void {
  openSheet('installmentLink', { txnRef: ref });
}

/**
 * The other end of the link: from a payment she has just filed as قسط و وام to the plan it paid.
 * Her unfinished plans, the one of exactly this amount first; the one it already pays ticked; and a
 * way to make the plan right here — the only moment the app knows both its amount and its day.
 * Dismissing is an answer too: the payment stays filed and linked to nothing.
 */
function InstallmentLinkSheet({ txnRef }: { txnRef: string }) {
  const { view, plans } = usePlans();
  const close = useClose();
  // Back out of the form and she is back at the choice, not dropped out of both.
  const [creating, setCreating] = useState(false);
  const entry = view.allEntries.find((e) => e.txn.ref === txnRef) ?? null;
  useGone(entry == null, close);
  if (!entry) return null;

  if (creating) {
    return (
      <InstallmentSheet fromPayment={entry} onDismiss={() => setCreating(false)}
        onSave={(name, payment, count) => close(() => addInstallmentFrom(entry, name, payment, count))} />
    );
  }

  const amount = entry.txn.amountRial;
  const current = installmentPaidBy(txnRef, plans.installments);
  const offered = plans.installments
    .filter((p) => !p.done || p.plan.id === current?.plan.id)
    .sort((a, b) => Number(a.plan.targetRial !== amount) - Number(b.plan.targetRial !== amount));
  const link = (planId: string | null) => close(() => setInstallmentPayment(entry, planId));

  return (
    <Sheet onClose={() => close()} label="پرداخت کدوم قسطه؟">
      <SheetTitle>پرداخت کدوم قسطه؟</SheetTitle>
      <p class="plan-lede">
        {txnTitleFa(entry.txn) + '، ' + (amount != null ? bidi(`${faCompact(tomanOf(amount))} تومان`) + '، ' : '') + faDay(entry.txn.day)}
      </p>
      {offered.length === 0 ? (
        <>
          <p class="plan-first-plan">هنوز قسطی نساختی. اگه این پرداختِ یه قسط ماهانه‌ست، با همین بسازش تا ببینی چند تا مونده.</p>
          <div class="sheet-actions">
            <PillButton voice="primary" block label="ساختن قسط" onClick={() => setCreating(true)} />
            <PillButton block label="فعلاً نه" onClick={() => close()} />
          </div>
        </>
      ) : (
        <>
          <div class="mt-m" role="radiogroup">
            {offered.map((plan) => {
              const selected = plan.plan.id === current?.plan.id;
              return (
                <button type="button" role="radio" aria-checked={selected} class="link-row" key={plan.plan.id}
                  onClick={() => (selected ? close() : link(plan.plan.id))}>
                  <span class="radio" aria-hidden="true" />
                  <span class="grow">
                    <span class="plan-name">{plan.plan.nameFa}</span>
                    <span class="plan-sub">{installmentLineFa(plan) + (plan.plan.targetRial === amount ? ' • همین مبلغ' : '')}</span>
                  </span>
                </button>
              );
            })}
          </div>
          <div class="sheet-actions">
            <PillButton block label="+ قسط تازه با همین پرداخت" onClick={() => setCreating(true)} />
            {current && <PillButton block label="از قسط جداش کن" onClick={() => link(null)} />}
          </div>
        </>
      )}
    </Sheet>
  );
}

/**
 * The link as the payment's own page shows it: which plan it paid, or that it paid none, and the
 * card is the way to change either. Shown on a قسط و وام payment and on any payment already linked.
 * [current] is `installmentPaidBy(ref, plans.installments)`; [onOpen] usually `openInstallmentLink(ref)`.
 */
export function InstallmentLinkRow({ current, onOpen }: { current: InstallmentProgress | null; onOpen: () => void }) {
  return (
    <button type="button" class="link-card" onClick={onOpen}>
      <span class="grow">
        <span class="plan-name">{current ? `پرداخت قسطِ ${current.plan.nameFa}` : 'به هیچ قسطی وصل نیست'}</span>
        <span class="plan-sub">{current ? installmentLineFa(current) : 'وصلش کن تا توی قسط‌ها حساب بشه.'}</span>
      </span>
      <span class="link-verb">{current ? 'تغییر' : 'وصل کن'}</span>
    </button>
  );
}

// ─────────────────────────── «ارزش داشت؟» ───────────────────────────

/**
 * The weekly question, asked about at most two large discretionary purchases — never rent, bills,
 * fees or cash. [onAnswer] gets a WorthIt value; the caller passes it to `answerWorthIt`.
 */
export function WorthItCard({ entry, onAnswer, class: cls = '' }: { entry: LedgerEntry; onAnswer: (answer: string) => void; class?: string }) {
  return (
    <Panel class={`worth-it ${cls}`}>
      <p class="plan-sub">{faDay(entry.txn.day)}</p>
      <p class="worth-title">{entry.txn.merchant.trim() ? entry.txn.merchant : entry.categoryFa}</p>
      {entry.txn.amountRial != null && <p class="figure worth-amount">{bidi(`${faCompact(tomanOf(entry.txn.amountRial))} تومان`)}</p>}
      <p class="worth-ask">ارزش داشت؟</p>
      <div class="worth-answers">
        {/* Three routine answers, so three equal cells of one row — none of them is the commit. */}
        {WORTH_IT_ANSWERS.map(([label, value]) => <PillButton key={value} label={label} onClick={() => onAnswer(value)} />)}
      </div>
    </Panel>
  );
}

registerTab('BUDGET', { Component: BudgetScreen });
registerSheet('budget', BudgetSheet);
registerSheet('goal', GoalSheet);
registerSheet('installment', InstallmentRoute);
registerSheet('installmentPayments', InstallmentPaymentsSheet);
registerSheet('installmentLink', InstallmentLinkSheet);
registerSheet('cheque', ChequeSheet);
