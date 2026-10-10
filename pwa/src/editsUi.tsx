/**
 * «ویرایش تراکنش» and «تقسیم بین دسته‌ها» (EditsUi.kt) — the figure, the day and whose it was on
 * one of her own rows, and one payment broken into the things it bought.
 */
import { useEffect, useState } from 'preact/hooks';
import './settings.css';
import { ActIcon, CategoryIcon, glyphOf, hueCss } from './categoryIcon';
import type { ActGlyph } from './categoryIcon';
import { CategoryGrid } from './categoryGrid';
import { ledger, useLedger } from './derived';
import { MAX_SPLIT_PARTS } from './edits';
import type { SplitSpec } from './edits';
import { useFamily } from './family';
import { bidi, faCompact, faNumber, tomanOf } from './format';
import { rialToField, tomanFieldToRial } from './installments';
import { editTxn, revertTxnEdits, splitTxn } from './ledger';
import { CAT_TRANSFER, CAT_UNCATEGORISED, categoryChoices } from './rules';
import { closeSheet, registerSheet } from './nav';
import { DayStepper } from './manualTxn';
import { CategoryDisc, MemberFace, glyphsOf, useTehranDay } from './timeline';
import { AmountField, PillButton, Sheet, SheetLabel, SheetTitle } from './ui';
import { Chevron, PlusMark } from './icons';
import type { LedgerEntry, SplitPart } from './model';

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
    // Never greyed: the field already says what is wrong with the figure, and the tap takes the
    // keyboard off those words.
    (document.activeElement as HTMLElement | null)?.blur();
    if (rial == null) return;
    editTxn(entry, rial !== txn.amountRial ? rial : null, day !== txn.day ? day : null,
      member !== entry.ownerMemberId && members.length > 1 ? member : null);
    closeSheet();
  };

  return (
    <Sheet label="ویرایش تراکنش">
      <SheetTitle>ویرایش تراکنش</SheetTitle>

      <SheetLabel>چقدر، به تومان</SheetLabel>
      <AmountField ariaLabel="مبلغ به تومان" raw={amount} onRaw={setAmount} decimals={1}
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
        <PillButton label="ذخیره" voice="primary" block onClick={save} />
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
  // Raised by a save tap: a part with no category or no figure is only wrong once she has asked for
  // the split to be saved — until then it is just not filled in yet.
  const [missing, setMissing] = useState(false);
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
    // Never greyed: a tap that cannot save marks every part still missing something, with the
    // keyboard out of the way of the words.
    (document.activeElement as HTMLElement | null)?.blur();
    setMissing(true);
    if (!complete) return;
    const parts: SplitSpec = ids.map((id, i) => [id!, i === 0 ? firstRial : rest[i - 1]!]);
    splitTxn(entry, parts);
    closeSheet();
  };

  return (
    <Sheet label="تقسیم بین دسته‌ها">
      <SheetTitle>تقسیم بین دسته‌ها</SheetTitle>
      {/* Exact, not compact: she is reconciling a receipt, and «۶ میلیون» over parts that add to
          ۶٬۰۱۲٬۰۰۰ would read as a split that does not add up. */}
      <p class="muted" style={{ marginTop: 'var(--xs)' }}>{bidi(`کل تراکنش ${faNumber(tomanOf(total))} تومان`)}</p>

      {/* Each part in the anatomy دفتر gives the row it becomes — the category's disc, its name —
          so the sheet already shows the rows a save will list. Unchosen, it is the grey dots of a
          row still waiting. Disc and name are one target that opens the grid under it. */}
      <div class="split-parts">
        {ids.map((id, i) => {
          const name = id ? names.get(id) ?? 'دسته‌بندی نشده' : null;
          const over = i === 0 && firstRial <= 0;
          const sub = over ? 'بخش‌های دیگه از کل تراکنش بیشتر شدن.'
            : missing && id == null ? 'دسته‌اش رو انتخاب کن.'
              : i === 0 ? 'باقی مبلغ' : null;
          return (
            <div class="split-part" key={i}>
              <div class="split-head">
                <button type="button" class="split-pick" aria-expanded={picking === i}
                  onClick={() => setPicking(picking === i ? null : i)}>
                  <CategoryDisc markFa={name ?? 'دسته‌بندی نشده'} />
                  <span class="split-text">
                    <span class="split-name-line">
                      <span class={`split-name ellipsis${name ? '' : ' unchosen'}`}>{name ?? 'انتخاب دسته'}</span>
                      <span class={`split-chev${picking === i ? ' open' : ''}`}><Chevron size={20} /></span>
                    </span>
                    {sub && <span class={`split-sub${over || id == null ? ' error' : ''}`} role="status">{sub}</span>}
                  </span>
                </button>
                {i === 0 && !over && <span class="figure split-figure">{faNumber(tomanOf(firstRial))}</span>}
                {i > 0 && ids.length > 2 && (
                  <PillButton label="حذف" voice="danger" onClick={() => {
                    setIds(ids.filter((_, j) => j !== i)); setAmounts(amounts.filter((_, j) => j !== i)); setPicking(null);
                  }} />
                )}
              </div>
              {picking === i && (
                <div class="split-grid">
                  <CategoryGrid categories={choices.filter((c) => c.id === id || !chosen.includes(c.id))} selected={id}
                    selectedLabel="انتخاب‌شده" onSelect={(c) => { setIds(setAt(ids, i, c.id)); setPicking(null); }} />
                </div>
              )}
              {i > 0 && (
                <div class="split-amount">
                  <AmountField label="چقدر، به تومان" ariaLabel={`مبلغ ${name ?? `بخش ${faNumber(i + 1)}`}`} raw={amounts[i]}
                    decimals={1} onRaw={(v) => setAmounts(setAt(amounts, i, v))}
                    error={!amounts[i].trim() ? (missing ? 'مبلغش رو بنویس.' : null)
                      : rest[i - 1] == null ? 'مبلغ رو فقط با عدد بنویس.' : null} />
                </div>
              )}
            </div>
          );
        })}
        {ids.length < MAX_SPLIT_PARTS && (
          // The grid opens with the part: a category is the first thing a new part needs.
          <div>
            <button type="button" class="split-add" onClick={() => {
              setIds([...ids, null]); setAmounts([...amounts, '']); setPicking(ids.length);
            }}>
              <span class="split-add-disc"><PlusMark size={18} /></span>
              یه بخش دیگه
            </button>
          </div>
        )}
      </div>

      <div class="sheet-actions">
        <PillButton label="ذخیره تقسیم" voice="primary" block onClick={save} />
        {!!entry.split?.length && <PillButton label="یکی‌اش کن" block onClick={() => { splitTxn(entry, []); closeSheet(); }} />}
        <PillButton label="انصراف" block onClick={closeSheet} />
      </div>
    </Sheet>
  );
}

/** The parts of a split row, as the transaction page lists them under its category. */
export function SplitPanel({ split }: { split: SplitPart[] }) {
  const custom = glyphsOf(ledger());
  return (
    <div class="details">
      {split.map((p) => {
        const glyph = glyphOf(p.categoryFa, custom);
        return (
          <div class="detail-row split-panel-row" key={p.categoryId}>
            <span><CategoryIcon glyph={glyph} size={18} color={hueCss(glyph)} />{p.categoryFa}</span>
            <span class="figure">{bidi(`${faCompact(tomanOf(p.rial))} تومان`)}</span>
          </div>
        );
      })}
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
      {onDelete && <div class="set-band"><DeleteRow id={entry.txn.ref} onConfirmed={onDelete} /></div>}
    </div>
  );
}

export function ActRow({ title, glyph, value = null, onClick }: { title: string; glyph: ActGlyph; value?: string | null; onClick: () => void }) {
  return (
    <button type="button" class="set-row" onClick={onClick}>
      <span class="set-disc"><ActIcon glyph={glyph} /></span>
      <span class="set-text"><span class="set-title one" style={{ display: 'block' }}>{title}</span></span>
      {value != null && <span class="set-value">{value}</span>}
      <span class="chev"><Chevron size={24} /></span>
    </button>
  );
}

/**
 * Error ink until the first tap; then the row fills with error and its name becomes the question. A
 * person's page in طلب و بدهی ends on the same row.
 */
export function DeleteRow({ id, onConfirmed, label = 'حذف این تراکنش' }: { id: string; onConfirmed: () => void; label?: string }) {
  const [armed, setArmed] = useState(false);
  // A row reused for another transaction never arrives armed.
  useEffect(() => setArmed(false), [id]);
  return (
    <button type="button" class={`set-row danger${armed ? ' armed' : ''}`}
      onClick={() => { if (armed) { setArmed(false); onConfirmed(); } else setArmed(true); }}>
      <span class="set-disc"><ActIcon glyph="TRASH" /></span>
      <span class="set-text"><span class="set-title" style={{ display: 'block' }} aria-live="polite">
        {armed ? 'مطمئنی؟ برای حذف دوباره بزن' : label}
      </span></span>
    </button>
  );
}

registerSheet('editTxn', EditTxnSheet);
registerSheet('splitTxn', SplitSheet);
