/**
 * «تراکنش دستی» (ManualTxnUi.kt) — the door for money no message will ever report: cash handed
 * over, a دنگ paid back, the fruit seller with no terminal. And the day stepper the transaction
 * page borrows to correct a day typed in wrong.
 */
import { useEffect, useState } from 'preact/hooks';
import { CategoryGrid } from './categoryGrid';
import { useLedger } from './derived';
import { addManualTxn } from './ledger';
import { MAX_NOTE_CHARS, categoryChoices } from './rules';
import { tomanFieldToRial } from './installments';
import { tehranDay, tehranDayStart } from './jalali';
import { faClock, faDay, faWeekdayDate } from './format';
import { closeSheet, openSheet, registerSheet } from './nav';
import { AmountField, PillButton, SegmentedChoice, Sheet, SheetLabel, SheetTitle, TextField } from './ui';
import './timeline.css';
import './manualTxn.css';

/** The clock field's keystrokes as it keeps them: ASCII digits, from either digit set, nothing else. */
export function clockDigits(text: string): string {
  return text.replace(/[۰-۹]/g, (c) => String(c.charCodeAt(0) - 0x6f0))
    .replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x660)).replace(/\D/g, '');
}

/**
 * «۱۴:۰۳» — or the field's own «۱۴۰۳» — read back into milliseconds since Tehran midnight, or
 * null. Four digits, hour then minute; the colon is the field's to draw, never hers to find. A
 * lone hour or three digits is not accepted: guessing is how a transaction lands at the wrong minute.
 */
export function parseFaClock(text: string): number | null {
  const digits = clockDigits(text);
  if (digits.length !== 4) return null;
  const hour = Number(digits.slice(0, 2)); const minute = Number(digits.slice(2));
  if (hour > 23 || minute > 59) return null;
  return (hour * 60 + minute) * 60_000;
}

/**
 * Digits drawn as they are written (digitsMask, ManualTxnUi.kt): Persian, with [sep] before each
 * index in [at] once a digit sits there — the clock's «۱۴:۰۳», a cheque's «۱۴۰۵/۰۹/۱۵».
 */
const shownDigits = (digits: string, sep: string, at: number[]): string =>
  [...digits].map((d, i) => (at.includes(i) ? sep : '') + String.fromCharCode(0x6f0 + Number(d))).join('');

/**
 * A fixed count of digits on the number pad, the separators drawn for her. Focus selects the whole
 * value, since the usual edit is a different one typed over it; and it turns red only once she has
 * left it, because every retyped value passes through shorter ones on the way.
 */
export function DigitsField({ digits, onDigits, length, sep, at, label, ariaLabel, hint }: {
  digits: string; onDigits: (digits: string) => void; length: number; sep: string; at: number[];
  label: string; ariaLabel: string; hint: string | null;
}) {
  const [focused, setFocused] = useState(false);
  const red = hint != null && !focused;
  const shown = (d: string) => shownDigits(d, sep, at);
  const onInput = (e: Event) => {
    const el = e.currentTarget as HTMLInputElement;
    let next = clockDigits(el.value);
    let before = clockDigits(el.value.slice(0, el.selectionStart ?? el.value.length)).length;
    // A backspace that only took a drawn separator meant the digit in front of it.
    if (next === digits && el.value.length < shown(digits).length && before > 0) {
      next = next.slice(0, before - 1) + next.slice(before); before--;
    }
    // One digit too many is refused rather than pushing one off the end.
    if (next.length > length) { next = digits; before--; }
    onDigits(next);
    // Written back by hand: a refused keystroke changes no state, so nothing would re-render it.
    el.value = shown(next);
    const pos = Math.min(Math.max(before, 0), next.length);
    const caret = pos + at.filter((a) => a < pos).length;
    el.setSelectionRange(caret, caret);
  };
  return (
    <div>
      <label class={`field clock-field${red ? ' error' : ''}`}>
        <input value={shown(digits)} dir="ltr" inputMode="numeric" aria-label={ariaLabel} aria-invalid={red}
          onInput={onInput} onBlur={() => setFocused(false)}
          onFocus={(e) => { const el = e.currentTarget; setFocused(true); setTimeout(() => el.select()); }} />
        <span class="label">{label}</span>
      </label>
      {hint && (red ? <div key="error" class="field-support error" role="status">{hint}</div>
        : <div key="hint" class="field-support">{hint}</div>)}
    </div>
  );
}

/** The clock as four digits, the colon drawn for her. */
function ClockField({ digits, onDigits }: { digits: string; onDigits: (digits: string) => void }) {
  return (
    <DigitsField digits={digits} onDigits={onDigits} length={4} sep=":" at={[2]} label="ساعت" ariaLabel="ساعت تراکنش"
      hint={parseFaClock(digits) == null ? 'ساعت رو چهاررقمی بنویس، مثل ۰۹:۳۰.' : null} />
  );
}

/**
 * A transaction's day, one step at a time — almost always today or a few days back, and the
 * platform's picker is a Gregorian grid, the wrong calendar to ask this question in. «روز بعد» dims
 * at [today]: the ledger records what happened, and tomorrow has not.
 */
export function DayStepper({ day, today, onDay }: { day: number; today: number; onDay: (day: number) => void }) {
  return (
    <div class="day-stepper">
      <button type="button" class="pill" onClick={() => onDay(day - 1)}>روز قبل</button>
      <div class="grow" aria-live="polite">
        <div class="day-main">{faDay(day, today)}</div>
        {/* «امروز» is an answer, not a date — the date it stands for is stated under it. */}
        {day >= today - 1 && <div class="day-sub">{faWeekdayDate(day)}</div>}
      </div>
      <button type="button" class="pill" disabled={day >= today} onClick={() => onDay(day + 1)}>روز بعد</button>
    </div>
  );
}

/**
 * The answers in the order she knows them: which way, how much, what for, then the day and the
 * minute the bank would normally stamp — already filled with now, so the common case is no taps.
 */
function ManualTxnSheet() {
  const view = useLedger();
  const [outgoing, setOutgoing] = useState(true);
  const [amount, setAmount] = useState('');
  const [merchant, setMerchant] = useState('');
  const [note, setNote] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [openedAt] = useState(Date.now);
  const today = tehranDay(openedAt);
  const [day, setDay] = useState(today);
  const [clock, setClock] = useState(() => clockDigits(faClock(openedAt)));
  // Raised by a save tap: the grid cannot flag itself, and an untouched amount refused in silence.
  const [missingCategory, setMissingCategory] = useState(false);
  const [missingAmount, setMissingAmount] = useState(false);

  const rial = tomanFieldToRial(amount);
  const sinceMidnight = parseFaClock(clock);
  const choices = categoryChoices(view.categories, outgoing ? 'out' : 'in', view.categoryUse);
  // Flipping the direction swaps the grid, and a pick from the other side must not survive
  // invisibly — a خرج filed under «حقوق» is money on the wrong side of every report.
  useEffect(() => {
    if (categoryId != null && !choices.some((c) => c.id === categoryId)) setCategoryId(null);
  }, [outgoing]);

  const save = (): void => {
    // The pill never greys out — a dead button explains nothing — so a tap that cannot save says why.
    // Focus lets go first: the keyboard was covering the words, and the clock reddens only once left.
    (document.activeElement as HTMLElement | null)?.blur();
    setMissingCategory(categoryId == null);
    setMissingAmount(!amount.trim());
    if (rial == null || sinceMidnight == null || categoryId == null) return;
    addManualTxn(outgoing ? -rial : rial, categoryId, merchant, note, tehranDayStart(day) + sinceMidnight);
    closeSheet();
  };

  return (
    <Sheet label="تراکنش دستی">
      <SheetTitle>تراکنش دستی</SheetTitle>
      {/* The browser's own way in for a bank message, since nothing here reads the inbox. */}
      <div class="paste-link"><PillButton label="متن پیامک رو داری؟ بچسبونش" onClick={() => openSheet('pasteSms')} /></div>

      <SheetLabel>خرج بود یا دخل؟</SheetLabel>
      <SegmentedChoice options={[true, false]} selected={outgoing} label={(it) => (it ? 'خرج' : 'دخل')} onSelect={setOutgoing} />

      <SheetLabel>چقدر، به تومان</SheetLabel>
      {/* Spelled out under it, like every amount field: digits are easy to misread by ten. */}
      <AmountField label="مثلاً ۴۵۰ هزار" ariaLabel="مبلغ به تومان" raw={amount} onRaw={setAmount} decimals={1}
        error={!amount.trim() ? (missingAmount ? 'مبلغش رو بنویس.' : null)
          : rial == null ? 'این عدد قابل خوندن نیست. فقط عدد وارد کن.' : null} />

      <SheetLabel>بابت چی؟</SheetLabel>
      <TextField label="مثلاً میوه‌فروشی — خالی هم می‌شه" ariaLabel="بابت چی؟" value={merchant} maxLength={60} onInput={(v) => setMerchant(v.slice(0, 60))} />

      <SheetLabel>دسته‌بندی</SheetLabel>
      <CategoryGrid categories={choices} selected={categoryId} onSelect={(c) => setCategoryId(c.id)} selectedLabel="انتخاب‌شده" />
      {missingCategory && categoryId == null && <p class="grid-error" role="status">دسته‌اش رو انتخاب کن.</p>}

      <SheetLabel>کِی؟</SheetLabel>
      <DayStepper day={day} today={today} onDay={setDay} />
      <div style={{ marginTop: 'var(--m)' }}>
        <ClockField digits={clock} onDigits={setClock} />
      </div>

      <SheetLabel>توضیحات</SheetLabel>
      <TextField label="یادداشت — خالی هم می‌شه" ariaLabel="توضیحات" value={note} maxLength={MAX_NOTE_CHARS} multiline onInput={(v) => setNote(v.slice(0, MAX_NOTE_CHARS))} />

      <div class="sheet-actions">
        <PillButton voice="primary" block label="ثبت تراکنش" onClick={save} />
        <PillButton block label="انصراف" onClick={closeSheet} />
      </div>
    </Sheet>
  );
}

registerSheet('manualTxn', ManualTxnSheet);
