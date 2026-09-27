/**
 * طلب و بدهی on screen — the hero's strip, the آینده section, the list page, one person's page, and
 * the four sheets behind them: a person, a move, a move taken back, and «به کی؟» for a bank row.
 * The words and every figure are loans.ts's; this file only lays them out.
 *
 * Pages and sheets resolve their person fresh on every render, as the آینده sheets do: a person
 * deleted under an open sheet resolves to nothing and the sheet closes.
 */
import { Fragment } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { TOMAN_ID, resolveType } from './catalog';
import type { AssetType } from './catalog';
import { CategoryIcon, hueCss } from './categoryIcon';
import { currentEffective } from './data';
import { useLedger } from './derived';
import { faCompact, faDate, faDay, faHeld, faNumber, faSignedCompact, faWordsToman, ltrFigure, parseAmount, today as tehranToday, tomanOf } from './format';
import { AssetIcon } from './homeSheets';
import { Chevron, PersonMark, PlusMark } from './icons';
import { tomanFieldToRial } from './installments';
import { jalaliMonthsAfter } from './jalali';
import {
  MAX_LOAN_NAME, addLoanMove, addLoanPerson, addLoanPersonFrom, deleteLoanMove, deleteLoanPerson, editLoanPerson, faDayMonth,
  loanAfterFa, loanAmountFa, loanEventSubFa, loanEventTitleFa, loanHoldingFor, loanLinkRial, loanRialFa, loanSideFa,
  loanSubFa, loanTotals, loanViews, loanWhatFa, restoreLoanMove, restoreLoanPerson, setLoanLink, txnTitleFa,
} from './loans';
import type { LoanEvent, LoanSide, LoanView } from './loans';
import type { LedgerEntry } from './model';
import { closePage, closeSheet, navState, openPage, openSheet, registerPage, registerSheet, showNotice } from './nav';
import { batch, pref, useData } from './state';
import {
  AmountField, ChipChoice, EmptyState, PillButton, Screen, ScreenTitle, Section, SegmentedChoice, Sheet, SheetDelete,
  SheetLabel, SheetTitle, SwitchRow, TextField,
} from './ui';
import './loans.css';

// ─────────────────────────── shared reads ───────────────────────────

/** Every person and where they stand, at the rates the hero total uses. Re-renders on every write. */
function useLoans() {
  const view = useLedger();
  const views = loanViews(pref('loans'), view.loanLinks, view.entries, currentEffective());
  return { view, views, totals: loanTotals(views) };
}

const typeOf = (id: string): AssetType => resolveType(id, pref('rates')?.coins ?? []);

/** loanAmountFa's words without the figure: «سکه امامی», «گرم طلای ۱۸ عیار», «دلار آمریکا». */
const unitWordsFa = (type: AssetType): string =>
  (type.unitFa === 'عدد' || type.fa.startsWith(type.unitFa) ? type.fa : `${type.unitFa} ${type.fa}`);

/** The short word beside a figure: the unit, or for a counted thing the first word of its name («سکه»). */
const unitShortFa = (type: AssetType): string => (type.unitFa !== 'عدد' ? type.unitFa : type.fa.split(' ')[0]);

/** «۴۷۵٫۵ میلیون» as its digits and its magnitude, so the word can be set smaller beside the digits. */
function compactParts(toman: number): [digits: string, magnitude: string | null] {
  const text = faCompact(toman);
  const at = text.indexOf(' ');
  return at < 0 ? [text, null] : [text.slice(0, at), text.slice(at + 1)];
}

function Compact({ toman, class: cls }: { toman: number; class: string }) {
  const [digits, magnitude] = compactParts(toman);
  return <span class={`figure ${cls}`}>{digits}{magnitude && <span class="unit">{magnitude}</span>}</span>;
}

/** Her name for them, as the one letter that marks the row. */
function Letter({ name }: { name: string }) {
  return <span class="loan-letter" aria-hidden="true">{Array.from(name.trim())[0] ?? ''}</span>;
}

/** Assets she names by id, in the words the row uses: «سکه امامی، دلار آمریکا». */
const namesFa = (ids: readonly string[]): string => ids.map((id) => typeOf(id).fa).join('، ');

/** A sheet's way out, once — budgetUi's: a second closeSheet() would pop the page underneath too. */
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

/** Closes the sheet when what it was opened on is gone. */
function useGone(gone: boolean, close: () => void): void {
  useEffect(() => { if (gone) close(); }, [gone]);
}

const openNewPerson = (): void => openSheet('loanAccount');

// ─────────────────────────── on the hero ───────────────────────────

/**
 * «طلبت» and «بدهیت» under the total, never in it — one well, one tap to the list. Nothing at all
 * until somebody owes or is owed; the caller leaves it out of the household reading.
 */
export function LoansHeroStrip() {
  const { totals } = useLoans();
  if (totals.isEmpty) return null;
  const said = [
    ...(totals.owedPeople > 0 ? [`${faCompact(totals.owedToman)} تومان طلب`] : []),
    ...(totals.owePeople > 0 ? [`${faCompact(totals.oweToman)} تومان بدهی`] : []),
  ];
  return (
    <button type="button" class="loan-strip" aria-label={`طلب و بدهی: ${said.join('، ')}`} onClick={() => openPage('loans')}>
      {totals.owedPeople > 0 && <span class="grow"><span class="k">طلبت</span><Compact toman={totals.owedToman} class="v" /></span>}
      {totals.owePeople > 0 && <span class="grow"><span class="k">بدهیت</span><Compact toman={totals.oweToman} class="v" /></span>}
      <Chevron size={18} />
    </button>
  );
}

// ─────────────────────────── on آینده ───────────────────────────

/** The fourth band of آینده: the way in, or the way to start. Wears the tab's own row shapes. */
export function LoansSection() {
  const { views, totals } = useLoans();
  return (
    <>
      <h2 class="plan-section">طلب و بدهی</h2>
      {views.length === 0 ? (
        <>
          <p class="plan-empty">قرضی که به کسی دادی یا ازش گرفتی، کنار جمع دارایی‌هات نگه داشته می‌شه، نه توش.</p>
          <button type="button" class="plan-row plan-add" style={{ borderRadius: 'var(--r-group)' }} onClick={openNewPerson}>
            <span class="figure plan-plus" aria-hidden="true">+</span>
            <span>اولین حساب</span>
          </button>
        </>
      ) : (
        <button type="button" class="plan-row loan-door" style={{ borderRadius: 'var(--r-group)' }} onClick={() => openPage('loans')}>
          <span class="grow">
            {totals.owedPeople > 0 && <span class="loan-door-line">{`${faCompact(totals.owedToman)} تومان طلب داری`}</span>}
            {totals.owePeople > 0 && <span class="loan-door-line">{`${faCompact(totals.oweToman)} تومان بدهکاری`}</span>}
            {totals.isEmpty && <span class="loan-door-line">حساب همه صافه</span>}
            <span class="loan-people">{`${faNumber(views.length)} نفر`}</span>
          </span>
          <Chevron size={18} />
        </button>
      )}
    </>
  );
}

// ─────────────────────────── the list ───────────────────────────

const SIDES: Array<[LoanSide, string]> = [['OWED', 'بهت بدهکارن'], ['OWE', 'بهشون بدهکاری'], ['SETTLED', 'تسویه شده']];

function LoansPage() {
  const { views, totals } = useLoans();
  const today = tehranToday();
  if (views.length === 0) {
    return (
      <Screen title="طلب و بدهی" back tabs={false}>
        <EmptyState icon={<PersonMark size={28} />} title="هنوز حسابی نداری."
          body="قرضی که دادی یا گرفتی رو اینجا بنویس، یا وقتی یه واریز رو «قرض» می‌زنی بگو مال کی بوده.">
          <div style={{ marginTop: 'var(--l)' }}><PillButton voice="primary" label="حساب تازه" onClick={openNewPerson} /></div>
        </EmptyState>
      </Screen>
    );
  }
  const units = views.some((v) => v.units.size > 0);
  return (
    <Screen title="طلب و بدهی" back tabs={false}>
      <div class="loan-pair">
        <div>
          <p class="k">طلبت</p>
          <Compact toman={totals.owedToman} class="v" />
          <p class="s">{`پیش ${faNumber(totals.owedPeople)} نفر`}</p>
        </div>
        <div>
          <p class="k">بدهیت</p>
          <Compact toman={totals.oweToman} class="v" />
          <p class="s">{`به ${faNumber(totals.owePeople)} نفر`}</p>
        </div>
      </div>
      <p class="loan-note">{(units ? 'به نرخ امروز. ' : '') + 'توی جمع دارایی‌هات حساب نمی‌شه.'}</p>
      {totals.missing.length > 0 && (
        <p class="loan-note caution">{`نرخ ${namesFa(totals.missing)} نرسیده؛ توی این جمع نیست.`}</p>
      )}
      {SIDES.map(([side, title]) => {
        const rows = views.filter((v) => v.side === side);
        return rows.length > 0 && (
          <Fragment key={side}>
            <Section title={title} />
            <div class="band">{rows.map((v) => <PersonRow key={v.person.id} view={v} today={today} />)}</div>
          </Fragment>
        );
      })}
      <div class="band" style={{ marginTop: 'var(--xl)' }}>
        <button type="button" class="band-row add-row loan-add" onClick={openNewPerson}><PlusMark size={20} />حساب تازه</button>
      </div>
    </Screen>
  );
}

/** One person: who, the one line worth reading under the name, and today's value when there is one. */
function PersonRow({ view, today }: { view: LoanView; today: number }) {
  const sub = loanSubFa(view, today, typeOf);
  // Nothing priced is not «۰»: the caution under the pair names what could not be valued.
  const figure = view.side !== 'SETTLED' && !(view.toman === 0 && view.missing.length > 0);
  return (
    <button type="button" class="band-row loan-row" onClick={() => openPage('loanPerson', { id: view.person.id })}>
      <Letter name={view.person.name} />
      <span class="grow">
        <span class="loan-name ellipsis">{view.person.name}</span>
        {sub && (
          <span class={`loan-sub${sub[1] ? ' caution' : ''}`}>
            {sub[1] && <span class="loan-dot" aria-hidden="true" />}{sub[0]}
          </span>
        )}
      </span>
      {figure && <Compact toman={Math.abs(view.toman)} class="loan-amount" />}
    </button>
  );
}

// ─────────────────────────── one person ───────────────────────────

function LoanPersonPage({ id }: { id: string }) {
  const { views } = useLoans();
  const today = tehranToday();
  const view = views.find((v) => v.person.id === id);
  // Deleted from its own sheet, which closes this page with it.
  if (!view) return null;
  const { person, side } = view;
  const settled = side === 'SETTLED';
  const edit = () => openSheet('loanAccount', { id });
  const move = (giving: boolean) => openSheet('loanMove', { personId: id, giving });
  // [label, giving]: the likely next thing first — money back from whoever owes.
  const [first, second]: Array<[string, boolean]> = side === 'OWED' ? [['پس داد', false], ['بیشتر دادم', true]]
    : side === 'OWE' ? [['پس دادم', true], ['بیشتر گرفتم', false]]
    : [['دادم', true], ['گرفتم', false]];
  return (
    <div class="screen no-tabs">
      <div class="loan-top">
        <PillButton label="ویرایش" onClick={edit} />
        <PillButton label="برگشت" onClick={closePage} />
      </div>
      <div class="loan-who">
        <Letter name={person.name} />
        <ScreenTitle>{person.name}</ScreenTitle>
      </div>
      <p class="loan-lead">{loanSideFa(side)}</p>
      {!settled && <Answer view={view} />}
      <div class="loan-actions">
        <PillButton voice={settled ? 'tonal' : 'primary'} label={first[0]} onClick={() => move(first[1])} />
        <PillButton label={second[0]} onClick={() => move(second[1])} />
      </div>
      {!settled && (person.promise != null ? <PromiseRow promise={person.promise} today={today} onClick={edit} /> : (
        <div style={{ marginTop: 'var(--l)' }}><PillButton label="+ قرار پس دادن" onClick={edit} /></div>
      ))}
      <Trail view={view} today={today} />
    </div>
  );
}

/**
 * What is owed, in what was lent: one line a part, the units first and the Rial last, the first at
 * the page's size and the rest a step down behind «و». Toman is only the valuation under it.
 */
function Answer({ view }: { view: LoanView }) {
  const parts: Array<[digits: string, words: string]> = [...view.units].map(([id, amount]) => {
    const type = typeOf(id);
    return [faHeld(Math.abs(amount), type.dec), unitWordsFa(type)];
  });
  const cash = tomanOf(Math.abs(view.rial));
  if (view.rial !== 0) {
    const [digits, magnitude] = compactParts(cash);
    parts.push([digits, magnitude ? `${magnitude} تومان` : 'تومان']);
  }
  const words = view.units.size === 0 ? faWordsToman(cash) : null;
  return (
    <>
      {parts.map(([digits, unit], i) => (
        <p key={i} class={`figure loan-owed${i > 0 ? ' later' : ''}`}>{i > 0 ? 'و ' : ''}{digits}<span class="u">{unit}</span></p>
      ))}
      {words && <p class="loan-words">{words}</p>}
      {view.units.size > 0 && (
        <p class="loan-value">
          <span class="dot" aria-hidden="true" />
          <span>امروز روی هم <b class="figure">{`${faCompact(Math.abs(view.toman))} تومان`}</b></span>
        </p>
      )}
      {view.missing.length > 0 && <p class="loan-value caution">{`نرخ ${namesFa(view.missing)} نرسیده`}</p>}
    </>
  );
}

/** The date he gave, and how far off it is — overdue in words and caution ink, never colour alone. */
function PromiseRow({ promise, today, onClick }: { promise: number; today: number; onClick: () => void }) {
  const left = promise - today;
  return (
    <button type="button" class="loan-promise" onClick={onClick}>
      <span class="grow">قرار پس دادن: <b>{faDayMonth(promise, today)}</b></span>
      <span class={left < 0 ? 'caution' : 'muted'}>
        {left > 0 ? `${faNumber(left)} روز مونده` : left === 0 ? 'امروز' : `${faNumber(-left)} روز گذشته`}
      </span>
    </button>
  );
}

/** «ریز حساب»: every line that makes the balance, by day, newest first — the bank's and hers. */
function Trail({ view, today }: { view: LoanView; today: number }) {
  if (view.events.length === 0 && view.olderRial === 0) return null;
  const days: Array<[number, LoanEvent[]]> = [];
  for (const e of view.events) {
    const last = days.at(-1);
    if (last && last[0] === e.day) last[1].push(e); else days.push([e.day, [e]]);
  }
  return (
    <>
      <Section title="ریز حساب" />
      {days.map(([day, events]) => (
        <Fragment key={day}>
          <div class="day-heading"><h2 class="grow">{faDay(day, today)}</h2></div>
          {events.map((e) => <EventRow key={e.entry?.txn.ref ?? e.move?.id} event={e} />)}
        </Fragment>
      ))}
      {view.olderRial !== 0 && (
        <p class="loan-note">{`و ${loanRialFa(view.olderRial)} از تراکنش‌هایی که دیگه توی دفتر نیستن.`}</p>
      )}
    </>
  );
}

/**
 * One line of the trail, in the ledger's own convention: money out plain with «−», money in green
 * with «+». Cash wears قرض's and پس‌گرفتن قرض's marks; a unit wears its asset's. A bank row opens its
 * transaction; a line she wrote opens the sheet that takes it back.
 */
function EventRow({ event }: { event: LoanEvent }) {
  const out = event.typeId ? event.amount > 0 : event.rial > 0;
  const type = event.typeId ? typeOf(event.typeId) : null;
  const sub = loanEventSubFa(event);
  const amount = type
    ? `${ltrFigure((out ? '−' : '+') + faHeld(Math.abs(event.amount), type.dec))} ${unitShortFa(type)}`
    : faSignedCompact(tomanOf(Math.abs(event.rial)), !out);
  const glyph = out ? 'LEND' : 'PAYBACK';
  const open = () => (event.entry
    ? openPage('txn', { txnRef: event.entry.txn.ref })
    : event.move && openSheet('loanMoveDelete', { moveId: event.move.id }));
  return (
    <button type="button" class="txn-row" onClick={open}>
      {type ? <span class="loan-asset"><AssetIcon type={type} size={44} /></span> : (
        <span class="txn-disc" style={{ '--hue': hueCss(glyph) }}>
          <CategoryIcon glyph={glyph} size={22} stroke={1.8} color="var(--hue)" />
        </span>
      )}
      <span class="grow txn-text">
        <span class="txn-title ellipsis">{loanEventTitleFa(event, typeOf)}</span>
        {sub && <span class="txn-line"><span class="ellipsis">{sub}</span></span>}
      </span>
      <span class={`figure txn-amount${out ? '' : ' gain'}`}>{amount}</span>
    </button>
  );
}

// ─────────────────────────── the sheets ───────────────────────────

/** A move's size as she typed it: whole Rial for «نقد», units of the asset otherwise. */
function parseMove(unit: string, text: string): { rial: number; amount: number } | null {
  if (!unit) {
    const rial = tomanFieldToRial(text);
    return rial == null ? null : { rial, amount: 0 };
  }
  const amount = parseAmount(text);
  return amount != null && amount > 0 && Number.isFinite(amount) ? { rial: 0, amount } : null;
}

/**
 * What a move can be in: «نقد» first, then what this person already owes in, then what she holds by
 * hand, then the three people lend most — each once. Cash she holds is «نقد» already.
 */
function unitChoices(owed: Iterable<string>): string[] {
  const held = pref('holdings').filter((h) => h.wallet == null && h.amount > 0 && h.typeId !== TOMAN_ID).map((h) => h.typeId);
  return [...new Set(['', ...owed, ...held, 'usd', 'coin_emami', 'gold18'])];
}

const UNREADABLE = 'این عدد قابل خوندن نیست. فقط عدد وارد کن.';

/** The unit chips and the amount under them, the unit's own word at the field's end. */
function UnitAmount({ units, unit, onUnit, text, onText, error }: {
  units: string[]; unit: string; onUnit: (unit: string) => void; text: string; onText: (text: string) => void; error: string | null;
}) {
  const type = unit ? typeOf(unit) : null;
  const suffix = type ? unitShortFa(type) : 'تومان';
  return (
    <>
      <div style={{ marginTop: 'var(--m)' }}>
        <ChipChoice options={units} selected={unit} label={(u) => (u ? typeOf(u).fa : 'نقد')} scroll
          onSelect={(u) => { if (u !== unit) onText(''); onUnit(u); }} />
      </div>
      <div class="loan-amount-field">
        <AmountField key={unit} raw={text} onRaw={onText} decimals={type ? type.dec : 1} words={!type} ariaLabel={`چقدر، به ${suffix}`}
          error={error ?? (text.trim() !== '' && parseMove(unit, text) == null ? UNREADABLE : null)} />
        <span class="suffix" aria-hidden="true">{suffix}</span>
      </div>
    </>
  );
}

type Opening = 'OWED' | 'OWE';
const OPENINGS: Opening[] = ['OWED', 'OWE'];
const OPENING_FA: Record<Opening, string> = { OWED: 'بهم بدهکاره', OWE: 'بهش بدهکارم' };

type PromiseChoice = 'NONE' | 'WEEK' | 'MONTH' | 'QUARTER';
const PROMISES: PromiseChoice[] = ['NONE', 'WEEK', 'MONTH', 'QUARTER'];
const PROMISE_FA: Record<PromiseChoice, string> = { NONE: 'بدون قرار', WEEK: 'یه هفته', MONTH: 'یه ماه', QUARTER: 'سه ماه' };
const promiseDay = (choice: PromiseChoice, today: number): number | null =>
  choice === 'WEEK' ? today + 7 : choice === 'MONTH' ? jalaliMonthsAfter(today, 1) : choice === 'QUARTER' ? jalaliMonthsAfter(today, 3) : null;

/**
 * 'loanAccount' {id?}: a new account — a name, what is owed (nothing owed is only an empty row under
 * «تسویه شده»), and a date if he gave one — or the same person's name and date, and delete. A new one opens straight onto its page.
 */
function PersonSheet({ id }: { id?: string }) {
  useData();
  const close = useClose();
  const editing = id != null ? pref('loans').people.find((p) => p.id === id) ?? null : null;
  useGone(id != null && editing == null, close);
  const [today] = useState(tehranToday);
  const [name, setName] = useState(editing?.name ?? '');
  const [opening, setOpening] = useState<Opening>('OWED');
  const [unit, setUnit] = useState('');
  const [text, setText] = useState('');
  const [promise, setPromise] = useState<number | null>(editing?.promise ?? null);
  if (id != null && editing == null) return null;

  const carried = editing ? null : parseMove(unit, text);
  const ready = name.trim() !== '' && (editing != null || carried != null);
  const title = editing ? 'ویرایش حساب' : 'حساب تازه';
  const picked = PROMISES.find((c) => promiseDay(c, today) === promise) ?? 'CUSTOM';

  const save = () => {
    if (!ready) return;
    if (editing) return close(() => editLoanPerson(editing.id, name, promise));
    const sign = opening === 'OWED' ? 1 : -1;
    const made = addLoanPerson(name, promise, carried && { typeId: unit, rial: carried.rial * sign, amount: carried.amount * sign });
    // The page takes the sheet's place, so back from it lands where she started.
    if (made) openPage('loanPerson', { id: made });
  };
  const remove = () => {
    if (!editing) return;
    const gone = deleteLoanPerson(editing.id);
    // The page is this person's too: both go, and the notice stands over whatever is under them.
    if (navState().pages.at(-1)?.id === 'loanPerson') closePage(); else close();
    if (gone) showNotice(`حساب ${gone[0].name} پاک شد`, { label: 'برگردون', run: () => restoreLoanPerson(...gone) });
  };

  return (
    <Sheet onClose={() => close()} label={title}>
      <SheetTitle>{title}</SheetTitle>
      <div style={{ marginTop: 'var(--l)' }}>
        <TextField label="اسم" value={name} maxLength={MAX_LOAN_NAME} onInput={(v) => setName(v.slice(0, MAX_LOAN_NAME))} autoFocus={!editing} />
      </div>
      {!editing && (
        <>
          <SheetLabel>کی بدهکاره؟</SheetLabel>
          <SegmentedChoice options={OPENINGS} selected={opening} label={(o) => OPENING_FA[o]} onSelect={setOpening} fontSize={14} />
          <UnitAmount units={unitChoices([])} unit={unit} onUnit={setUnit} text={text} onText={setText} error={null} />
        </>
      )}
      <SheetLabel>قرار پس دادن</SheetLabel>
      <ChipChoice<string> options={PROMISES} selected={picked} label={(c) => PROMISE_FA[c as PromiseChoice]}
        onSelect={(c) => setPromise(promiseDay(c as PromiseChoice, today))} />
      {promise != null && (
        // Not DayStepper: a promise is a day to come, and that one stops at today.
        <div class="day-stepper" style={{ marginTop: 'var(--m)' }}>
          <button type="button" class="pill" onClick={() => setPromise(promise - 1)}>روز قبل</button>
          <div class="grow" aria-live="polite"><div class="day-main">{faDate(promise)}</div></div>
          <button type="button" class="pill" onClick={() => setPromise(promise + 1)}>روز بعد</button>
        </div>
      )}
      <div class="sheet-actions">
        <PillButton voice="primary" block label="ذخیره" disabled={!ready} onClick={save} />
      </div>
      {editing && <SheetDelete label="پاک کردن حساب" onConfirmed={remove} />}
    </Sheet>
  );
}

const NEW = '\u0000new';

/**
 * 'loanMove' {personId?, giving, typeId?}: something handed over or taken back that no bank reported.
 * Opened from a holding there is no person yet, so it asks «به کی؟» first; the unit is the holding's.
 */
function MoveSheet({ personId, giving, typeId }: { personId?: string; giving: boolean; typeId?: string }) {
  const { views } = useLoans();
  const close = useClose();
  const [who, setWho] = useState<string>(personId ?? (views.length === 0 ? NEW : ''));
  const [newName, setNewName] = useState('');
  const [unit, setUnit] = useState(!typeId || typeId === TOMAN_ID ? '' : typeId);
  const [text, setText] = useState('');
  const [moveHolding, setMoveHolding] = useState(true);
  const person = views.find((v) => v.person.id === who) ?? null;
  useGone(personId != null && person == null, close);
  if (personId != null && person == null) return null;

  const parsed = parseMove(unit, text);
  const holding = loanHoldingFor(pref('holdings'), unit, giving);
  const switchShown = holding != null || (!giving && unit !== '');
  const moving = switchShown && moveHolding;
  // In the holding's own units: Toman for cash, the asset's otherwise.
  const size = parsed == null ? null : unit ? parsed.amount : tomanOf(parsed.rial);
  const over = giving && moving && holding != null && size != null && size > holding.amount + 1e-9;
  const title = personId == null ? 'قرض دادم' : giving ? `به ${person!.person.name} دادی` : `از ${person!.person.name} گرفتی`;
  const ready = parsed != null && !over && (person != null || (who === NEW && newName.trim() !== ''));

  const save = () => {
    if (!ready || parsed == null) return;
    batch(() => {
      const target = person?.person.id ?? addLoanPerson(newName, null, null);
      if (target) addLoanMove(target, unit, parsed.rial, parsed.amount, giving, moving);
    });
    close();
  };

  return (
    <Sheet onClose={() => close()} label={title}>
      <SheetTitle>{title}</SheetTitle>
      {personId == null && (
        <>
          <SheetLabel>به کی؟</SheetLabel>
          <ChipChoice options={[...views.map((v) => v.person.id), NEW]} selected={who}
            label={(o) => (o === NEW ? '+ یه نفر تازه' : views.find((v) => v.person.id === o)?.person.name ?? '')} onSelect={setWho} />
          {who === NEW && (
            <div style={{ marginTop: 'var(--m)' }}>
              <TextField label="اسم" value={newName} maxLength={MAX_LOAN_NAME} autoFocus onInput={(v) => setNewName(v.slice(0, MAX_LOAN_NAME))} />
            </div>
          )}
        </>
      )}
      <UnitAmount units={unitChoices(person?.units.keys() ?? [])} unit={unit} onUnit={setUnit} text={text} onText={setText}
        error={over ? `توی دارایی‌هات فقط ${loanAmountFa(typeOf(unit || TOMAN_ID), holding!.amount)} هست.` : null} />
      {switchShown && (
        <div style={{ marginTop: 'var(--s)' }}>
          <SwitchRow title={giving ? 'از دارایی‌هام کم کن' : 'به دارایی‌هام اضافه کن'} checked={moveHolding} onChange={setMoveHolding} />
        </div>
      )}
      <div class="sheet-actions">
        <PillButton voice="primary" block label="ثبت" disabled={!ready} onClick={save} />
      </div>
    </Sheet>
  );
}

/** 'loanMoveDelete' {moveId}: a line she wrote, taken back — and its coins back where they came from. */
function MoveDeleteSheet({ moveId }: { moveId: string }) {
  useData();
  const close = useClose();
  const move = pref('loans').moves.find((m) => m.id === moveId) ?? null;
  useGone(move == null, close);
  if (!move) return null;
  const event: LoanEvent = { day: move.day, typeId: move.typeId, rial: move.rial, amount: move.amount, entry: null, move };
  return (
    <Sheet onClose={() => close()} label="این مورد رو پاک کنم؟">
      <SheetTitle>این مورد رو پاک کنم؟</SheetTitle>
      <p class="loan-sheet-sub">{`${loanEventTitleFa(event, typeOf)}، ${faDay(move.day)}`}</p>
      <div style={{ marginTop: 'var(--l)' }}>
        <SheetDelete label="پاکش کن" onConfirmed={() => close(() => {
          const gone = deleteLoanMove(move.id);
          if (gone) showNotice('پاک شد', { label: 'برگردون', run: () => restoreLoanMove(gone) });
        })} />
      </div>
    </Sheet>
  );
}

/** Opens «به کی؟» for one transaction. `txnRef`, not `ref`: Preact takes `ref` out of a component's props. */
export function openLoanLink(ref: string): void {
  openSheet('loanLink', { txnRef: ref });
}

/**
 * 'loanLink' {txnRef}: whose money a bank row was. Every person with where they stand now, and for
 * the one she picks, where they would stand after — said before she commits.
 */
function LinkSheet({ txnRef }: { txnRef: string }) {
  const { view, views } = useLoans();
  const close = useClose();
  const entry = view.allEntries.find((e) => e.txn.ref === txnRef) ?? null;
  const link = view.loanLinks.get(txnRef);
  const current = link && views.some((v) => v.person.id === link.personId) ? link.personId : null;
  const [picked, setPicked] = useState<string | null>(current);
  const [fresh, setFresh] = useState(views.length === 0);
  const [name, setName] = useState('');
  useGone(entry == null, close);
  if (!entry) return null;

  const delta = loanLinkRial(entry);
  const title = entry.txn.direction === 'in' ? 'این پول رو کی داد؟' : 'این پول رو به کی دادی؟';
  const amount = entry.txn.amountRial != null ? [`${faCompact(tomanOf(entry.txn.amountRial))} تومان`] : [];
  const save = () => {
    if (fresh) { if (name.trim()) close(() => addLoanPersonFrom(entry, name)); }
    else if (picked != null && picked !== current) close(() => setLoanLink(entry, picked));
  };

  return (
    <Sheet onClose={() => close()} label={title}>
      <SheetTitle>{title}</SheetTitle>
      <p class="loan-sheet-sub">{[txnTitleFa(entry.txn), ...amount, faDay(entry.txn.day)].join('، ')}</p>
      {views.length > 0 && (
        <div role="radiogroup" style={{ marginTop: 'var(--m)' }}>
          {views.map((v) => {
            const selected = !fresh && v.person.id === picked;
            return (
              <button type="button" role="radio" aria-checked={selected} class="link-row loan-link-row" key={v.person.id}
                onClick={() => { setPicked(v.person.id); setFresh(false); }}>
                <span class="radio" aria-hidden="true" />
                <span class="grow">
                  <span class="plan-name">{v.person.name}</span>
                  <span class="plan-sub">{v.side === 'SETTLED' ? loanSideFa(v.side) : `الان ${loanSideFa(v.side)}: ${loanWhatFa(v, typeOf)}`}</span>
                  {selected && v.person.id !== current && delta != null && (
                    <span class="loan-preview" aria-live="polite">{loanAfterFa(v, delta, typeOf)}</span>
                  )}
                </span>
              </button>
            );
          })}
        </div>
      )}
      {fresh && (
        <div style={{ marginTop: 'var(--m)' }}>
          <TextField label="اسم" value={name} maxLength={MAX_LOAN_NAME} autoFocus onInput={(v) => setName(v.slice(0, MAX_LOAN_NAME))} onEnter={save} />
        </div>
      )}
      <div class="sheet-actions">
        <PillButton voice="primary" block label={fresh ? 'ساختن و وصل کردن' : 'ثبت'} onClick={save}
          disabled={fresh ? name.trim() === '' : picked == null || picked === current} />
        {!fresh && <PillButton block label="+ یه نفر تازه" onClick={() => setFresh(true)} />}
        {current != null && <PillButton block label="جداش کن" onClick={() => close(() => setLoanLink(entry, null))} />}
      </div>
    </Sheet>
  );
}

/**
 * The link as the transaction's own page shows it: whose money it was and where they stand now, or
 * that it is nobody's yet — and the card is the way to change either. The installment card's shape.
 */
export function LoanLinkRow({ entry }: { entry: LedgerEntry }) {
  const { view, views } = useLoans();
  const link = view.loanLinks.get(entry.txn.ref);
  const person = link ? views.find((v) => v.person.id === link.personId) ?? null : null;
  return (
    <button type="button" class="link-card" onClick={() => openLoanLink(entry.txn.ref)}>
      <span class="grow">
        <span class="plan-name">{person ? `${entry.txn.direction === 'in' ? 'از' : 'به'} ${person.person.name}` : 'به کسی وصل نیست'}</span>
        <span class="plan-sub">
          {!person ? 'وصلش کن تا توی طلب و بدهی حساب بشه.'
            : person.side === 'SETTLED' ? loanSideFa(person.side) : `${loanSideFa(person.side)}: ${loanWhatFa(person, typeOf)}`}
        </span>
      </span>
      <span class="link-verb">{person ? 'تغییر' : 'وصل کن'}</span>
    </button>
  );
}

registerPage('loans', LoansPage);
registerPage('loanPerson', LoanPersonPage);
registerSheet('loanAccount', PersonSheet);
registerSheet('loanMove', MoveSheet);
registerSheet('loanMoveDelete', MoveDeleteSheet);
registerSheet('loanLink', LinkSheet);
