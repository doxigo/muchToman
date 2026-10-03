/**
 * The one door the phone does not have. A browser cannot read the SMS inbox, so she pastes the
 * bank's message here and says which bank sent it — the answer the phone reads off the sender.
 * What was read is shown before anything is stored, in the ledger's own words, so a misread
 * figure is caught on the sheet rather than in a report.
 *
 * `#paste=<urlencoded text>` opens the sheet prefilled: an iOS Shortcut can hand a message over
 * from the share sheet without her copying it at all.
 *
 * A Shortcut's bundle (paste.ts `readBundle`) is the other way in: many messages at once, each with
 * its sender, so the bank is read off the phone's own table and asked only for a sender it lacks.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import './timeline.css';
import './pasteUi.css';
import { ledger } from './derived';
import { addPastedBundle, addPastedSms } from './ledger';
import { bodyToStore, parsePasted, readBundle, severalMessages, writeBundle } from './paste';
import type { Bundled } from './paste';
import { PICKABLE_BANKS, bankOf, isBank } from './sms';
import { BankLogo } from './logos';
import { bidi, faCompact, faDigits, faNumber, faSignedParts, faWordsToman, tomanOf } from './format';
import { closeSheet, openSheet, registerSheet, showNotice } from './nav';
import { pref } from './state';
import { PillButton, Sheet, SheetLabel, SheetTitle, SignedFigure, TextField } from './ui';
import { openTxn } from './timeline';

function PasteSheet({ text: given = '' }: { text?: string }) {
  const [text, setText] = useState(given);
  // A second hand-off while the sheet is up replaces what is in it.
  useEffect(() => setText(given), [given]);
  const [bank, setBank] = useState(() => pref('lastPasteBank'));
  const [missingText, setMissingText] = useState(false);
  const [missingBank, setMissingBank] = useState(false);
  const [saving, setSaving] = useState(false);
  const [bundleAdded, setBundleAdded] = useState(0);

  const blank = !text.trim();
  // A bundle is read message by message, each held to the tests the store applies; one the store
  // would refuse is counted and left. The bank chips answer for one unknown sender at a time, so
  // one tap never names two senders' messages.
  const bundle = blank ? null : readBundle(text);
  const keep = bundle?.filter((r) => !severalMessages(r.body) && bodyToStore(r.body) != null) ?? [];
  const asking = keep.find((r) => bankOf(r.sender) == null)?.sender;
  // Refused live, the same tests the store applies: several messages at once, a one-time code, or
  // a body naming no money. Several is asked first, so a huge paste is never parsed per keystroke.
  const several = !blank && !bundle && severalMessages(text);
  const refused = !blank && !bundle && !several && bodyToStore(text) == null;
  const ok = !blank && !bundle && !several && !refused;
  const read = ok ? parsePasted(text) : null;

  const save = async (): Promise<void> => {
    // Never greyed: a tap that cannot save says why, with the keyboard out of the way of the words.
    // The one guard is against a second tap while the first is still writing.
    if (saving) return;
    (document.activeElement as HTMLElement | null)?.blur();
    setMissingText(blank);
    const answered = isBank(bank) || (bundle != null && asking == null);
    setMissingBank(!answered);
    if (bundle) {
      if (!keep.length || !answered) return;
      setSaving(true);
      const added = bundleAdded + await addPastedBundle(keep, (sender) => bankOf(sender) ?? (sender === asking ? bank : null));
      setSaving(false);
      // Another unknown sender's messages stay in the field for their own answer, the chips cleared
      // so the last answer is not taken for theirs. The count waits for the sheet to close: a notice
      // now would sit over the commit she taps next.
      const left = keep.filter((r) => bankOf(r.sender) == null && r.sender !== asking);
      if (left.length) { setText(writeBundle(left)); setBank(''); setBundleAdded(added); return; }
      closeSheet();
      showNotice(added ? `${faNumber(added)} پیامک ثبت شد` : 'پیامک تازه‌ای نبود');
      return;
    }
    if (!ok || !isBank(bank)) return;
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
          error={missingText && blank ? 'متن پیامک رو اینجا بچسبون.'
            : bundle && !keep.length ? 'هیچ‌کدوم از این پیامک‌ها تراکنش بانکی نیست.'
            : several ? 'این بیشتر از یک پیامکه. هر بار فقط یکی رو بچسبون.'
            : refused ? 'این پیامک تراکنش بانکی نیست، یا رمز یک‌بارمصرفه.' : null}
          support={bundle && bundleLine(keep, bundle.length - keep.length)} />
      </div>
      {/* Where several at once is the wish: before anything is pasted, and right after she tried. */}
      {(blank || several) && (
        <div class="paste-how">
          <PillButton label="چطوری چندتا پیامک رو یه‌جا بیارم؟" onClick={() => openSheet('pasteShortcut', { text })} />
        </div>
      )}
      {read && <PastePreview read={read} />}

      {(!bundle || asking != null) && (
        <>
          <SheetLabel>
            {!bundle ? 'از کدوم بانک؟'
              : asking ? `پیام‌های ${bidi(faDigits(asking))} از کدوم بانکه؟` : 'پیام‌های بی‌فرستنده از کدوم بانکه؟'}
          </SheetLabel>
          <BankChips bank={bank} onBank={(b) => { setBank(b); setMissingBank(false); }} />
          {missingBank && !isBank(bank) && <p class="grid-error" role="status">بانکش رو انتخاب کن.</p>}
        </>
      )}

      <div class="sheet-actions">
        <PillButton label="ثبت" voice="primary" block onClick={() => void save()} />
        <PillButton label="انصراف" block onClick={closeSheet} />
      </div>
    </Sheet>
  );
}

/** What a bundle holds, in one line: «۱۲ پیام از ۳ بانک؛ ۲ تا از فرستنده‌ی ناشناس؛ ۱ تا تراکنش نبود». */
function bundleLine(keep: Bundled[], skipped: number): string {
  const banks = keep.map((r) => bankOf(r.sender)).filter((b) => b != null);
  const unknown = keep.length - banks.length;
  return [
    banks.length ? `${faNumber(banks.length)} پیام از ${faNumber(new Set(banks).size)} بانک` : '',
    unknown ? `${faNumber(unknown)} تا از فرستنده‌ی ناشناس` : '',
    skipped ? `${faNumber(skipped)} تا تراکنش نبود` : '',
  ].filter(Boolean).join('؛ ');
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
            {read.direction ? <SignedFigure parts={faSignedParts(toman, incoming)} /> : faCompact(toman)}{' '}<span class="unit">تومان</span>
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

/** An iOS label, in the Latin she sees on the screen, set apart from the Persian around it. */
const En = ({ children }: { children: string }) => <bdi class="ios">{children}</bdi>;

/**
 * How to build the two Shortcuts a bundle comes from (paste.ts `readBundle`): an automation that
 * writes each bank message to a file as it arrives, and a shortcut she runs to copy the file. The
 * labels are Shortcuts' English ones; what differs between iOS versions is said, not promised.
 */
function ShortcutSheet({ text = '' }: { text?: string }) {
  return (
    <Sheet label="چندتا پیامک با هم">
      <SheetTitle>چندتا پیامک با هم</SheetTitle>
      <p class="howto">
        آیفون پیامک‌ها رو به هیچ اپی نمی‌ده، ولی اپ <En>Shortcuts</En> خود آیفون می‌تونه هر پیامک بانک رو همین
        که رسید ته یه فایل بنویسه. بعد هر وقت خواستی، همه رو یه‌جا کپی می‌کنی و اینجا می‌چسبونی.
      </p>

      <SheetLabel>جمع کردن پیامک‌ها</SheetLabel>
      <ol class="howto">
        <li>توی <En>Shortcuts</En> برو <En>Automation</En>، دکمه‌ی <En>+</En> رو بزن و <En>Message</En> رو انتخاب کن.</li>
        <li>
          جلوی <En>Sender</En> فرستنده‌ی پیامک‌های بانک رو انتخاب کن. <En>Run Immediately</En> رو بزن
          و <En>Notify When Run</En> رو خاموش کن.
        </li>
        <li>
          یه اکشن <En>Text</En> اضافه کن و این دو خط رو توش بساز:
          <pre class="howto-code" dir="ltr">{'#muchtoman Sender Current Date\nContent'}</pre>
          <En>Sender</En> و <En>Content</En> رو از <En>Shortcut Input</En> بردار. فرمت <En>Current Date</En> رو
          بذار <En>ISO 8601</En>، تا پیامکی که تاریخ نداره سر روز خودش بشینه.
        </li>
        <li>
          یه اکشن <En>Append to Text File</En> اضافه کن که همین متن رو ته فایل <En>muchtoman.txt</En> بنویسه،
          با <En>Make New Line</En> روشن.
        </li>
        <li>برای هر فرستنده‌ی دیگه‌ی بانک هم همین رو تکرار کن.</li>
      </ol>

      <SheetLabel>آوردن به اینجا</SheetLabel>
      <ol class="howto">
        <li>
          یه میان‌بر معمولی بساز، نه <En>Automation</En>، با سه اکشن: <En>Get File</En> برای <En>muchtoman.txt</En>،
          بعد <En>Copy to Clipboard</En>، بعد <En>Delete Files</En> با <En>Confirm Before Deleting</En> خاموش.
        </li>
        <li>هر وقت خواستی اجراش کن، بیا اینجا و بچسبون. بانک هر پیامک از فرستنده‌اش معلوم می‌شه.</li>
      </ol>

      <p class="howto muted">
        بعضی نسخه‌های iOS قبل از اجرای خودکار یه اعلان نشون می‌دن که باید بزنیش؛ اگه <En>Run Immediately</En> رو
        نمی‌بینی، گوشیت از این‌هاست.
      </p>
      <p class="howto muted">
        پاک کردن فایل واجب نیست: پیامکی که یه بار ثبت شده دوباره ثبت نمی‌شه. اگه شماره‌ی بانک توی مخاطب‌هاته،
        به‌جاش اسم مخاطب میاد و موقع چسبوندن بانکش ازت پرسیده می‌شه.
      </p>

      <div class="sheet-actions">
        <PillButton label="برگرد به چسباندن" voice="primary" block onClick={() => openSheet('pasteSms', { text })} />
      </div>
    </Sheet>
  );
}
registerSheet('pasteShortcut', ShortcutSheet);

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
