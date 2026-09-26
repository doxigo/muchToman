/**
 * The one door the phone does not have. A browser cannot read the SMS inbox, so she pastes the
 * bank's message here and says which bank sent it — the answer the phone reads off the sender.
 * What was read is shown before anything is stored, in the ledger's own words, so a misread
 * figure is caught on the sheet rather than in a report.
 *
 * `#paste=<urlencoded text>` opens the sheet prefilled: an iOS Shortcut can hand a message over
 * from the share sheet without her copying it at all.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import './timeline.css';
import './pasteUi.css';
import { ledger } from './derived';
import { addPastedSms } from './ledger';
import { bodyToStore, parsePasted } from './paste';
import { PICKABLE_BANKS, isBank } from './sms';
import { BankLogo } from './logos';
import { bidi, faCompact, faSignedCompact, faWordsToman, tomanOf } from './format';
import { closeSheet, openSheet, registerSheet, showNotice } from './nav';
import { pref } from './state';
import { Sheet, SheetLabel, SheetTitle, TextField } from './ui';
import { openTxn } from './timeline';

function PasteSheet({ text: given = '' }: { text?: string }) {
  const [text, setText] = useState(given);
  // A second hand-off while the sheet is up replaces what is in it.
  useEffect(() => setText(given), [given]);
  const [bank, setBank] = useState(() => pref('lastPasteBank'));
  const [missingText, setMissingText] = useState(false);
  const [missingBank, setMissingBank] = useState(false);
  const [saving, setSaving] = useState(false);

  const blank = !text.trim();
  // Refused live, the same test the store applies: a one-time code, or a body naming no money.
  const refused = !blank && bodyToStore(text) == null;
  const read = blank || refused ? null : parsePasted(text);

  const save = async (): Promise<void> => {
    if (saving) return;
    setMissingText(blank);
    setMissingBank(!isBank(bank));
    if (blank || refused || !isBank(bank)) return;
    setSaving(true);
    const source = await addPastedSms(text, bank);
    setSaving(false);
    if (!source) return;
    closeSheet();
    const entry = ledger().entries.find((e) => e.txn.srcHash === source.id);
    showNotice('پیامک ثبت شد', entry ? { label: 'ببینش', run: () => openTxn(entry.txn.ref) } : undefined);
  };

  return (
    <Sheet label="پیامک بانک">
      <SheetTitle>پیامک بانک</SheetTitle>
      <div class="paste-text">
        <TextField label="متن پیامک" value={text} multiline autoFocus={blank} onInput={setText}
          error={missingText && blank ? 'متن پیامک رو اینجا بچسبون.' : refused ? 'این پیامک تراکنش بانکی نیست، یا رمز یک‌بارمصرفه.' : null} />
      </div>
      {read && <PastePreview read={read} />}

      <SheetLabel>از کدوم بانک؟</SheetLabel>
      <BankChips bank={bank} onBank={(b) => { setBank(b); setMissingBank(false); }} />
      {missingBank && !isBank(bank) && <p class="grid-error" role="status">بانکش رو انتخاب کن.</p>}

      <div class="sheet-actions">
        <button type="button" class={`pill wide${!blank && !refused && isBank(bank) ? ' primary' : ''}`} style={{ minHeight: '56px', fontSize: '16px' }}
          disabled={saving} onClick={() => void save()}>ثبت</button>
        <button type="button" class="pill wide" onClick={closeSheet}>انصراف</button>
      </div>
    </Sheet>
  );
}

/** What was read, in the words the transaction page uses for the same facts. */
function PastePreview({ read }: { read: ReturnType<typeof parsePasted> }) {
  const incoming = read.direction === 'in';
  const toman = read.amountRial == null ? null : tomanOf(read.amountRial);
  const rows: Array<[string, string]> = [];
  if (read.direction) rows.push(['نوع', incoming ? 'واریز' : 'برداشت']);
  if (read.balanceRial != null) rows.push(['مانده بعد از تراکنش', bidi(`${faCompact(tomanOf(read.balanceRial))} تومان`)]);
  if (read.feeRial != null && read.feeRial > 0) rows.push(['کارمزد', bidi(`${faCompact(tomanOf(read.feeRial))} تومان`)]);
  if (read.printedAt.trim()) rows.push(['زمان ثبت', bidi(read.printedAt)]);
  if (read.mask.trim()) rows.push(['کارت یا حساب', bidi(read.mask)]);
  if (toman == null && !rows.length) return null;
  return (
    <div class="paste-preview" aria-live="polite">
      {toman != null ? (
        <>
          {/* No sign where the message names no direction: a guessed «−» is a claim. */}
          <p class={`figure paste-amount${incoming ? ' gain' : ''}`}>
            {read.direction ? faSignedCompact(toman, incoming) : faCompact(toman)}{' '}<span class="unit">تومان</span>
          </p>
          {faWordsToman(toman) && <p class="muted small">{faWordsToman(toman)}</p>}
        </>
      ) : <p class="caution paste-no-amount">مبلغ در پیامک مشخص نشده</p>}
      {rows.length > 0 && (
        <div class="paste-rows">
          {rows.map(([label, value]) => <div class="detail-row" key={label}><span>{label}</span><span>{value}</span></div>)}
        </div>
      )}
    </div>
  );
}

/** The banks as chips on one scrolling line, the one she used last already chosen and in view. */
function BankChips({ bank, onBank }: { bank: string; onBank: (bank: string) => void }) {
  const line = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    line.current?.querySelector('[aria-checked="true"]')?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }, []);
  return (
    <div ref={line} class="chips scroll bank-chips" role="radiogroup" aria-label="از کدوم بانک؟">
      {PICKABLE_BANKS.map((b) => (
        <button type="button" key={b.name} class="chip bank-chip" role="radio" aria-checked={b.name === bank} onClick={() => onBank(b.name)}>
          <BankLogo bank={b.name} size={24} />
          {b.fa}
        </button>
      ))}
    </div>
  );
}

registerSheet('pasteSms', PasteSheet);

/** An iOS Shortcut's hand-off: the message in the hash, read once and taken out of the address. */
function pasteFromHash(): void {
  if (!location.hash.startsWith('#paste=')) return;
  const raw = location.hash.slice('#paste='.length);
  let text = raw;
  try { text = decodeURIComponent(raw); } catch { /* a stray % — keep the text as it came */ }
  history.replaceState(history.state, '', location.pathname + location.search);
  openSheet('pasteSms', { text });
}
if (typeof location !== 'undefined') {
  pasteFromHash();
  addEventListener('hashchange', pasteFromHash);
}
