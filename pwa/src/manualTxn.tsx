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
import { AmountField, SegmentedChoice, Sheet, SheetLabel, SheetTitle, TextField } from './ui';
import './timeline.css';
import './manualTxn.css';

/**
 * «۱۴:۰۳» read back into milliseconds since Tehran midnight, from either digit set, or null. The
 * one format faClock prints, so the field's opening value always round-trips. A lone hour is not
 * accepted: guessing «:۰۰» is how a transaction lands at the top of the day instead of where it was.
 */
export function parseFaClock(text: string): number | null {
  const ascii = text.trim().replace(/[۰-۹]/g, (c) => String(c.charCodeAt(0) - 0x6f0))
    .replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x660));
  const match = /^(\d{1,2}):(\d{2})$/.exec(ascii);
  if (!match) return null;
  const hour = Number(match[1]); const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return (hour * 60 + minute) * 60_000;
}

/**
 * A transaction's day, one step at a time — almost always today or a few days back, and the
 * platform's picker is a Gregorian grid, the wrong calendar to ask this question in. It stops at
 * [today]: the ledger records what happened, and tomorrow has not.
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
      <button type="button" class="pill" onClick={() => { if (day < today) onDay(day + 1); }}>روز بعد</button>
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
  const [clock, setClock] = useState(() => faClock(openedAt));
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
  const usable = rial != null && categoryId != null && sinceMidnight != null;

  const save = (): void => {
    // The pill never greys out — a dead button explains nothing — so a tap that cannot save says why.
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
      <button type="button" class="text-btn paste-link" onClick={() => openSheet('pasteSms')}>متن پیامک رو داری؟ بچسبونش</button>

      <SheetLabel>خرج بود یا دخل؟</SheetLabel>
      <SegmentedChoice options={[true, false]} selected={outgoing} label={(it) => (it ? 'خرج' : 'دخل')} onSelect={setOutgoing} />

      <SheetLabel>چقدر، به تومان</SheetLabel>
      {/* Spelled out under it, like every amount field: digits are easy to misread by ten. */}
      <AmountField label="مثلاً ۴۵۰ هزار" ariaLabel="مبلغ به تومان" raw={amount} onRaw={setAmount}
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
        <TextField label="ساعت" ariaLabel="ساعت تراکنش" value={clock} maxLength={5} onInput={(v) => setClock(v.slice(0, 5))}
          error={sinceMidnight == null ? 'ساعت رو مثل ۱۴:۳۰ بنویس.' : null} />
      </div>

      <SheetLabel>توضیحات</SheetLabel>
      <TextField label="یادداشت — خالی هم می‌شه" ariaLabel="توضیحات" value={note} maxLength={MAX_NOTE_CHARS} multiline onInput={(v) => setNote(v.slice(0, MAX_NOTE_CHARS))} />

      <div class="sheet-actions">
        <button type="button" class={`pill wide${usable ? ' primary' : ''}`} style={{ minHeight: '56px', fontSize: '16px' }} onClick={save}>ثبت تراکنش</button>
        <button type="button" class="pill wide" onClick={closeSheet}>انصراف</button>
      </div>
    </Sheet>
  );
}

registerSheet('manualTxn', ManualTxnSheet);
