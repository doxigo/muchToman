/**
 * «ویرایش تراکنش» and «تقسیم بین دسته‌ها» (EditsUi.kt) — the figure, the day and whose it was on
 * one of her own rows, and one payment broken into the things it bought.
 */
import { useEffect, useState } from 'preact/hooks';
import './settings.css';
import { ActIcon } from './categoryIcon';
import type { ActGlyph } from './categoryIcon';
import { CategoryGrid } from './categoryGrid';
import { useLedger } from './derived';
import { MAX_SPLIT_PARTS } from './edits';
import type { SplitSpec } from './edits';
import { useFamily } from './family';
import { bidi, faCompact, faNumber, tomanOf } from './format';
import { tomanFieldToRial } from './installments';
import { editTxn, revertTxnEdits, splitTxn } from './ledger';
import { CAT_TRANSFER, CAT_UNCATEGORISED, categoryChoices } from './rules';
import { closeSheet, registerSheet } from './nav';
import { DayStepper } from './manualTxn';
import { MemberFace, useTehranDay } from './timeline';
import { AmountField, PillButton, Sheet, SheetLabel, SheetTitle } from './ui';
import { Chevron } from './icons';
import type { LedgerEntry, SplitPart } from './model';

/** A Rial figure as the amount field holds it: Toman digits, a tenth only when there is one. */
export const rialToField = (rial: number): string =>
  rial % 10 === 0 ? String(rial / 10) : `${Math.trunc(rial / 10)}.${rial % 10}`;

/**
 * Nothing is saved until she says so, and what she leaves alone is not written: a sheet opened to
 * fix the day must not pin the figure it happened to show. On a pasted message's row the line under
 * the fields says what the correction does not touch — the account's balance is the bank's مانده.
 */
function EditTxnSheet({ txnRef }: { txnRef: string }) {
  const view = useLedger();
  const family = useFamily();
  const today = useTehranDay();
  const entry = view.allEntries.find((e) => e.txn.ref === txnRef);
  const txn = entry?.txn;
  const [amount, setAmount] = useState(txn?.amountRial != null ? rialToField(txn.amountRial) : '');
  const [day, setDay] = useState(txn?.day ?? today);
  const [member, setMember] = useState(entry?.ownerMemberId ?? '');
  if (!entry || !txn) return null;
  const rial = tomanFieldToRial(amount);
  const members = family.members;

  const save = (): void => {
    if (rial == null) return;
    editTxn(entry, rial !== txn.amountRial ? rial : null, day !== txn.day ? day : null,
      member !== entry.ownerMemberId && members.length > 1 ? member : null);
    closeSheet();
  };

  return (
    <Sheet label="ویرایش تراکنش">
      <SheetTitle>ویرایش تراکنش</SheetTitle>

      <SheetLabel>چقدر، به تومان</SheetLabel>
      <AmountField ariaLabel="مبلغ به تومان" raw={amount} onRaw={setAmount}
        error={rial == null ? 'مبلغ رو فقط با عدد بنویس.' : null} />

      <SheetLabel>کِی؟</SheetLabel>
      <DayStepper day={day} today={today} onDay={setDay} />

      {members.length > 1 && (
        <>
          <SheetLabel>خرج کی بود؟</SheetLabel>
          <div class="filter-chips" role="radiogroup" style={{ marginTop: 0 }}>
            {members.map((m) => (
              <button key={m.id} type="button" class="filter-chip" role="radio" aria-checked={m.id === member} onClick={() => setMember(m.id)}>
                <MemberFace name={m.name} avatar={m.avatar} size={24} />
                {m.name}
              </button>
            ))}
          </div>
        </>
      )}

      {txn.ref.startsWith('s:') && (
        <p class="txn-caption" style={{ marginTop: 'var(--l)' }}>
          گزارش‌ها و بودجه‌ها با عدد تو حساب می‌شن؛ موجودی حساب همون مانده‌ای می‌مونه که بانک گفته.
        </p>
      )}

      <div class="sheet-actions">
        <PillButton label="ذخیره" voice={rial != null ? 'primary' : 'tonal'} block onClick={save} />
        {entry.edited && (
          <PillButton label={txn.ref.startsWith('s:') ? 'برگردون به عدد پیامک' : 'برگردون به عدد اول'} block
            onClick={() => { revertTxnEdits(entry); closeSheet(); }} />
        )}
        <PillButton label="انصراف" block onClick={closeSheet} />
      </div>
    </Sheet>
  );
}

/**
 * The first part takes whatever the others leave, so the parts always add up to the row: she types
 * what the شیرینی cost and the rest stays خواربار. It starts as the row's own category.
 */
function SplitSheet({ txnRef }: { txnRef: string }) {
  const view = useLedger();
  const entry = view.allEntries.find((e) => e.txn.ref === txnRef);
  const [ids, setIds] = useState<Array<string | null>>(() =>
    entry?.split?.length ? entry.split.map((p) => p.categoryId)
      : [entry && entry.categoryId !== CAT_UNCATEGORISED ? entry.categoryId : null, null]);
  const [amounts, setAmounts] = useState<string[]>(() =>
    entry?.split?.length ? entry.split.map((p) => rialToField(p.rial)) : ['', '']);
  const [picking, setPicking] = useState<number | null>(null);
  if (!entry) return null;

  const total = entry.txn.amountRial ?? 0;
  const choices = categoryChoices(view.categories, entry.txn.direction, view.categoryUse).filter((c) => c.id !== CAT_TRANSFER);
  const names = new Map(view.managedCategories.map((c) => [c.id, c.nameFa]));
  const rest = amounts.slice(1).map(tomanFieldToRial);
  const firstRial = total - rest.reduce<number>((sum, r) => sum + (r ?? 0), 0);
  const chosen = ids.filter((id): id is string => id != null);
  const complete = ids.every((id) => id != null) && new Set(chosen).size === chosen.length &&
    rest.every((r) => r != null) && firstRial > 0;
  const setAt = <T,>(list: T[], i: number, v: T): T[] => list.map((x, j) => (j === i ? v : x));

  const save = (): void => {
    if (!complete) return;
    const parts: SplitSpec = ids.map((id, i) => [id!, i === 0 ? firstRial : rest[i - 1]!]);
    splitTxn(entry, parts);
    closeSheet();
  };

  return (
    <Sheet label="تقسیم بین دسته‌ها">
      <SheetTitle>تقسیم بین دسته‌ها</SheetTitle>
      <p class="muted" style={{ marginTop: 'var(--xs)' }}>{bidi(`کل تراکنش ${faCompact(tomanOf(total))} تومان`)}</p>

      {ids.map((id, i) => (
        <div key={i}>
          <SheetLabel>{i === 0 ? 'بخش اول، باقی مبلغ' : `بخش ${faNumber(i + 1)}`}</SheetLabel>
          <div class="row-flex">
            <div class="grow">
              <PillButton label={id ? names.get(id) ?? 'دسته‌بندی نشده' : 'انتخاب دسته'} voice={id ? 'tonal' : 'primary'} block
                onClick={() => setPicking(picking === i ? null : i)} />
            </div>
            {i > 0 && ids.length > 2 && (
              <PillButton label="حذف" voice="danger" onClick={() => {
                setIds(ids.filter((_, j) => j !== i)); setAmounts(amounts.filter((_, j) => j !== i)); setPicking(null);
              }} />
            )}
          </div>
          {picking === i && (
            <div style={{ marginTop: 'var(--m)' }}>
              <CategoryGrid categories={choices.filter((c) => c.id === id || !chosen.includes(c.id))} selected={id}
                selectedLabel="انتخاب‌شده" onSelect={(c) => { setIds(setAt(ids, i, c.id)); setPicking(null); }} />
            </div>
          )}
          <div style={{ marginTop: 'var(--s)' }}>
            {i === 0 ? (
              <p class="figure" style={{ fontWeight: 700, color: firstRial > 0 ? 'var(--on-surface)' : 'var(--error)' }}>
                {firstRial > 0 ? bidi(`${faCompact(tomanOf(firstRial))} تومان`) : 'بخش‌های دیگه از کل تراکنش بیشتر شدن.'}
              </p>
            ) : (
              <AmountField label="چقدر، به تومان" ariaLabel={`مبلغ بخش ${faNumber(i + 1)}`} raw={amounts[i]}
                onRaw={(v) => setAmounts(setAt(amounts, i, v))} />
            )}
          </div>
        </div>
      ))}

      {ids.length < MAX_SPLIT_PARTS && (
        <div style={{ marginTop: 'var(--l)' }}>
          <PillButton label="یه بخش دیگه" onClick={() => { setIds([...ids, null]); setAmounts([...amounts, '']); }} />
        </div>
      )}

      <div class="sheet-actions">
        <PillButton label="ذخیره تقسیم" voice={complete ? 'primary' : 'tonal'} block onClick={save} />
        {!!entry.split?.length && <PillButton label="یکی‌اش کن" block onClick={() => { splitTxn(entry, []); closeSheet(); }} />}
        <PillButton label="انصراف" block onClick={closeSheet} />
      </div>
    </Sheet>
  );
}

/** The parts of a split row, as the transaction page lists them under its category. */
export function SplitPanel({ split }: { split: SplitPart[] }) {
  return (
    <div class="details">
      {split.map((p) => (
        <div class="detail-row" key={p.categoryId}>
          <span>{p.categoryFa}</span>
          <span class="figure">{bidi(`${faCompact(tomanOf(p.rial))} تومان`)}</span>
        </div>
      ))}
    </div>
  );
}

/**
 * The foot of a transaction's page (EditsUi.kt `TxnActs`): what can be done to the row, in the
 * band تنظیمات speaks in — grey discs, because each acts on this row rather than leading anywhere.
 * Delete is a band of its own under the others, so the one act that loses something never sits a
 * slip from the two that do not; its two taps are worn by the row itself, the first turning it red
 * and saying — aloud — what the second will do.
 */
export function TxnActs({ entry, onEdit, onSplit, onDelete }: {
  entry: LedgerEntry; onEdit?: () => void; onSplit?: () => void; onDelete?: () => void;
}) {
  const parts = entry.split?.length ?? 0;
  return (
    <div class="txn-acts">
      {(onEdit || onSplit) && (
        <div class="set-band">
          {onEdit && <ActRow title="ویرایش تراکنش" glyph="PENCIL" value={entry.edited ? 'اصلاح‌شده' : null} onClick={onEdit} />}
          {onSplit && <ActRow title="تقسیم بین دسته‌ها" glyph="SPLIT" value={parts ? `${faNumber(parts)} دسته` : null} onClick={onSplit} />}
        </div>
      )}
      {onDelete && <div class="set-band"><DeleteRow txnRef={entry.txn.ref} onConfirmed={onDelete} /></div>}
    </div>
  );
}

function ActRow({ title, glyph, value, onClick }: { title: string; glyph: ActGlyph; value: string | null; onClick: () => void }) {
  return (
    <button type="button" class="set-row" onClick={onClick}>
      <span class="set-disc"><ActIcon glyph={glyph} /></span>
      <span class="set-text"><span class="set-title one" style={{ display: 'block' }}>{title}</span></span>
      {value != null && <span class="set-value">{value}</span>}
      <span class="chev"><Chevron size={24} /></span>
    </button>
  );
}

/** Error ink until the first tap; then the row fills with error and its name becomes the question. */
function DeleteRow({ txnRef, onConfirmed }: { txnRef: string; onConfirmed: () => void }) {
  const [armed, setArmed] = useState(false);
  // A row reused for another transaction never arrives armed.
  useEffect(() => setArmed(false), [txnRef]);
  return (
    <button type="button" class={`set-row danger${armed ? ' armed' : ''}`}
      onClick={() => { if (armed) { setArmed(false); onConfirmed(); } else setArmed(true); }}>
      <span class="set-disc"><ActIcon glyph="TRASH" /></span>
      <span class="set-text"><span class="set-title" style={{ display: 'block' }} aria-live="polite">
        {armed ? 'مطمئنی؟ برای حذف دوباره بزن' : 'حذف این تراکنش'}
      </span></span>
    </button>
  );
}

registerSheet('editTxn', EditTxnSheet);
registerSheet('splitTxn', SplitSheet);
